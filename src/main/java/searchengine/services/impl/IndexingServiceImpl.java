package searchengine.services.impl;

import lombok.RequiredArgsConstructor;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import searchengine.config.ConnectionData;
import searchengine.config.SitesList;
import searchengine.dto.indexing.IndexingResponse;
import searchengine.indexing.PageIndexer;
import searchengine.indexing.SiteInformationAdder;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.Status;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;
import searchengine.services.IndexingService;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


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
    private final ConnectionData connectionData;
    private static ArrayList<Thread> threads = new ArrayList<>();
    private ArrayList<ForkJoinPool> pools = new ArrayList<>();
    private static volatile boolean isCanceled = false;

    public static boolean isCanceled() {
        return isCanceled;
    }

    @Override
    public IndexingResponse startIndexing() {
        if (checkThreadIsAlive()) {
            return getFalseResponse("Индексация уже запущена");
        }
        threads.clear();
        pools.clear();
        for (int i = 0; i < sites.getSites().size(); i++) {
            int threadNumber = i;
            Runnable task = () -> {
                String url = SiteInformationAdder.getCorrectUrlFormat(sites.getSites().get(threadNumber).getUrl());
                indexSite(url, sites.getSites().get(threadNumber).getName());
            };
            Thread thread = new Thread(task);
            threads.add(thread);
        }
        threads.forEach(Thread::start);
        return getTrueResponse();
    }

    @Override
    public IndexingResponse stopIndexing() {
        if (!checkThreadIsAlive()) {
            return getFalseResponse("Индексация не запущена");
        }
        pools.forEach(ForkJoinPool::shutdownNow);
        isCanceled = true;
        for (ForkJoinPool pool : pools) {
            while (pool.isTerminating()) {}
        }
        threads.clear();
        pools.clear();
        isCanceled = false;
        siteRepository.findAll().forEach(this::addSiteFailedStatus);
        return getTrueResponse();
    }

    private void indexSite(String url, String name) {
        if (siteRepository.findByUrl(url).isPresent()) {
            url = deleteSiteInformation(url);
        }
        Site site = addNewSite(url, name, Status.INDEXING);
        ForkJoinPool forkJoinPool = new ForkJoinPool(numberOfCores);
        pools.add(forkJoinPool);
        String pages = forkJoinPool.invoke(new PageIndexer(
                new SiteInformationAdder(siteRepository, pageRepository, indexRepository, lemmaRepository, connectionData),
                site, url, new CopyOnWriteArrayList<>()));
        if (!site.getStatus().equals(Status.FAILED)) {
            site.setStatus(Status.INDEXED);
            site.setStatusTime(LocalDateTime.now());
            siteRepository.save(site);
        }
    }

    @Override
    public IndexingResponse indexOnePage (String url) {
        if (checkThreadIsAlive()) {
            return getFalseResponse("Индексация уже запущена");
        }
        url = SiteInformationAdder.getCorrectUrlFormat(url);
        String siteUrl = null;
        String siteName = null;
        boolean siteIsInConfig = false;
        for (searchengine.config.Site siteCfg : sites.getSites()) {
            if (url.startsWith(SiteInformationAdder.getCorrectUrlFormat(siteCfg.getUrl()))) {
                siteIsInConfig = true;
                siteUrl = SiteInformationAdder.getCorrectUrlFormat(siteCfg.getUrl());
                siteName = siteCfg.getName();
                break;
            }
        }
        if (!siteIsInConfig) {
            return getFalseResponse("Данная страница находится за пределами сайтов, указанных в конфигурационном файле");
        }
        Site site = getSiteForOnePageIndexing(url, siteUrl, siteName);
        var siteInfo = new SiteInformationAdder(siteRepository, pageRepository, indexRepository, lemmaRepository, connectionData);
        Document page = siteInfo.addOrUpdatePage(site,url);
        return getTrueResponse();
    }

    private Site getSiteForOnePageIndexing(String url, String siteUrl, String siteName) {
        URI uri = null;
        Logger logger = LogManager.getRootLogger();
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            logger.error(e.getMessage());
        }
        String domain = SiteInformationAdder.getCorrectUrlFormat(uri.getHost());

        if (siteRepository.findByUrl(domain).isPresent()) {
                return siteRepository.findByUrl(domain).get();
        }
        return addNewSite(siteUrl, siteName, Status.INDEXED);
    }


    private Site addNewSite (String url, String name, Status status) {
        Site site = new Site();
        site.setName(name);
        site.setUrl(url);
        site.setStatus(status);
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);
        return site;
    }

    private String deleteSiteInformation (String url) {
        Site siteForDelete = siteRepository.findByUrl(url).get();
        for (Page page : pageRepository.findAllBySite(siteForDelete)) {
            indexRepository.deleteAllByPage(page);
        }
        lemmaRepository.deleteAllBySite(siteForDelete);
        pageRepository.deleteAllBySite(siteForDelete);
        siteRepository.delete(siteForDelete);
        return url;
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

    public static boolean checkThreadIsAlive() {
        return threads.stream().anyMatch(Thread::isAlive);
    }

    private IndexingResponse getTrueResponse() {
        IndexingResponse response = new IndexingResponse();
        response.setResult(true);
        return response;
    }

    private IndexingResponse getFalseResponse(String message) {
        IndexingResponse response = new IndexingResponse();
        response.setResult(false);
        response.setError(message);
        return response;
    }
}