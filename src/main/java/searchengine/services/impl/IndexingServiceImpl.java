package searchengine.services.impl;

import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import searchengine.config.SitesList;
import searchengine.dto.indexing.IndexingResponse;
import searchengine.dto.indexing.RussianLemmaFinder;
import searchengine.model.*;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;
import searchengine.services.IndexingService;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;


@Service
@RequiredArgsConstructor
public class IndexingServiceImpl implements IndexingService {
    private static final int numberOfCores = Runtime.getRuntime().availableProcessors();
    @Autowired
    private SiteRepository siteRepository;
    @Autowired
    private PageRepository pageRepository;
    @Autowired
    private IndexRepository indexRepository;
    @Autowired
    private LemmaRepository lemmaRepository;
    private final SitesList sites;

    private static ArrayList<Thread> threads = new ArrayList<>();
    private ArrayList<ForkJoinPool> pools = new ArrayList<>();

    private volatile boolean isCanceled = false;

    private static RussianLemmaFinder russianLemmaFinder;

    static {
        try {
            russianLemmaFinder = new RussianLemmaFinder();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public IndexingResponse startIndexing() {
        IndexingResponse response = new IndexingResponse();

        if (threadIsAlive()) {
            response.setResult(false);
            response.setError("Индексация уже запущена");
            return response;
        }

        threads.clear();
        pools.clear();

        for (int i = 0; i < sites.getSites().size(); i++) {
            int threadNumber = i;
            Runnable task = () -> {
                String url = getCorrectUrlFormat(sites.getSites().get(threadNumber).getUrl());
                siteIndexer(url, sites.getSites().get(threadNumber).getName());
            };
            Thread thread = new Thread(task);
            threads.add(thread);
        }
        threads.forEach(Thread::start);

        response.setResult(true);
        return response;
    }

    @Override
    public IndexingResponse stopIndexing() {
        IndexingResponse response = new IndexingResponse();

        if (!threadIsAlive()) {
            response.setResult(false);
            response.setError("Индексация не запущена");
            return response;
        }

        pools.forEach(ForkJoinPool::shutdownNow);
        isCanceled = true;
        for (ForkJoinPool pool : pools) {
            while (pool.isTerminating()){
            }
        }
        threads.clear();
        pools.clear();
        isCanceled = false;

        siteRepository.findAll().forEach(this::addSiteFailedStatus);

        response.setResult(true);
        return response;
    }

    private void siteIndexer (String url, String name) {

         if (siteRepository.findByUrl(url).isPresent()) {
            Site siteForDelete = siteRepository.findByUrl(url).get();
            for (Page page : pageRepository.findAllBySite(siteForDelete)) {
                indexRepository.deleteAllByPage(page);
            }
            lemmaRepository.deleteAllBySite(siteForDelete);
            pageRepository.deleteAllBySite(siteForDelete);
            siteRepository.delete(siteForDelete);
         }
         Site site = new Site();
         site.setName(name);
         site.setUrl(url);
         site.setStatus(Status.INDEXING);
         site.setStatusTime(LocalDateTime.now());
         siteRepository.save(site);

         ForkJoinPool forkJoinPool = new ForkJoinPool(numberOfCores);
         pools.add(forkJoinPool);
         String pages = forkJoinPool.invoke(new PageIndexer(site, site.getUrl(), new CopyOnWriteArraySet<>()));

         if (!site.getStatus().equals(Status.FAILED)) {
             site.setStatus(Status.INDEXED);
             site.setStatusTime(LocalDateTime.now());
             siteRepository.save(site);
         }
    }

    @AllArgsConstructor
    private class PageIndexer extends RecursiveTask<String> {
        private Site site;
        private String url;
        private CopyOnWriteArraySet<String> allPages;

        @Override
        protected String compute() {

            StringBuilder stringBuilder = new StringBuilder(url + "\n");
            Set<PageIndexer> pageIndexers = new TreeSet<>(Comparator.comparing(o -> o.url));

            Document document = addPage(site, url);
            Elements elements = document.select("a[href]");
            for (Element element : elements) {
                if (isCanceled) {
                    break;
                }
                String attributeUrl = getCorrectUrlFormat(element.absUrl("href"));
                if (   (attributeUrl.startsWith(url))
                        && !attributeUrl.contains("#")
                        && !allPages.contains(attributeUrl) ) {
                    PageIndexer links = new PageIndexer(site, attributeUrl, allPages);
                    links.fork();
                    pageIndexers.add(links);
                    allPages.add(attributeUrl);
                }
            }
            for (PageIndexer link : pageIndexers) {
                stringBuilder.append(link.join());
            }
            return stringBuilder.toString();
        }
    }

    private Document addPage(Site site, String url) {
        try {
            Thread.sleep(150);
            Connection.Response connectionResponse = getConnectionResponse(url);
            Document document = connectionResponse.parse();
            String path = url.startsWith("https://www.") ?
                    url.substring(site.getUrl().length() - 1) :
                    url.substring(site.getUrl().length() - 5);
            if (!pageRepository.findByPathAndSite(path, site).isPresent()
                    && !pageRepository.findByPathAndSite(path.concat("/"), site).isPresent()) {
                Page page = new Page();
                page.setSite(site);
                page.setPath(path);
                page.setContent(document.toString());
                page.setCode(connectionResponse.statusCode());
                pageRepository.save(page);
                if (page.getCode() < 400) {
                    addRussianLemmas(document, site, page);
                }

                site.setStatusTime(LocalDateTime.now());
                siteRepository.save(site);
            }
            return document;

        } catch (InterruptedException | IOException e) {
            site.setLastError(e.getClass().getSimpleName().concat(" ").concat(e.getMessage()));
            site.setStatus(Status.FAILED);
            siteRepository.save(site);
        }
        return null;
    }

    private synchronized void addRussianLemmas(Document document, Site site, Page page) throws IOException {

        HashMap<String, Integer> lemmas = russianLemmaFinder.collectLemmas(document.toString());

        for (String lemmaText : lemmas.keySet()) {
            if (isCanceled) {
                break;
            }
            Lemma lemma;
            if (lemmaRepository.findByTextAndSite(lemmaText,site).isPresent()) {
                lemma = lemmaRepository.findByTextAndSite(lemmaText,site).get();
                lemma.setFrequency(lemma.getFrequency() + 1);
            }
            else {
                lemma = new Lemma();
                lemma.setSite(site);
                lemma.setFrequency(1);
            }
            lemma.setText(lemmaText);
            lemmaRepository.save(lemma);
            addIndex(lemma, page, lemmas.get(lemmaText));
        }
    }

    private void addIndex (Lemma lemma,Page page, int rank) {
        Index index = new Index();
        index.setPage(page);
        index.setLemma(lemma);
        index.setRank(rank);
        indexRepository.save(index);
    }

    private void addSiteFailedStatus (Site site) {
        if (site.getStatus().equals(Status.INDEXED)) {
            return;
        }
        site.setStatus(Status.FAILED);
        site.setLastError("Индексация остановлена пользователем");
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);
    }

    protected static String getCorrectUrlFormat (String url) {
        url = url.replace("http://", "https://");
        url = url.lastIndexOf("/") == url.length() - 1 ? url : url.concat("/");
        if (url.startsWith("https://www.")) {
            return url;
        }
        else if (url.startsWith("https://")) {
            return url.replace("https://", "https://www.");
        }
        else if (url.startsWith("www.")) {
            return "https://".concat(url);
        }
        else {
            return "https://www.".concat(url);
        }
    }

    protected static Connection.Response getConnectionResponse(String url) throws IOException {
        return Jsoup.connect(url)
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .userAgent("Mozilla/5.0 (Windows; U; WindowsNT 5.1; en-US; rv1.8.1.6) Gecko/20070725 Firefox/2.0.0.6")
                .referrer("http://www.google.com")
                .execute();
    }

    public static boolean threadIsAlive() {
        return threads.stream().anyMatch(Thread::isAlive);
    }

}

