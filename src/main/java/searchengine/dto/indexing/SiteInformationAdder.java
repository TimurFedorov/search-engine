package searchengine.dto.indexing;

import lombok.AllArgsConstructor;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import searchengine.model.*;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;
import searchengine.services.impl.IndexingServiceImpl;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;

@AllArgsConstructor
public class SiteInformationAdder {

    private SiteRepository siteRepository;
    private PageRepository pageRepository;
    private IndexRepository indexRepository;
    private LemmaRepository lemmaRepository;

    public Document addPage(Site site, String url) {
        try {
            Thread.sleep(150);
            Connection.Response connectionResponse = getConnectionResponse(url);
            Document document = connectionResponse.parse();
            String path = url.substring(site.getUrl().length() - 1);
            if (!pageRepository.findByPathAndSite(path, site).isPresent()) {
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

    public Document addOnePage (Site site, String url) {
        String path = url.substring(site.getUrl().length() - 1);
        if(!pageRepository.findByPathAndSite(path, site).isPresent()) {
            return addPage(site, url);
        } else {
            return updatePage(site, url, path);
        }
    }

    private Document updatePage (Site site, String url, String path) {
        try {
            Connection.Response connectionResponse = getConnectionResponse(url);
            Document document = connectionResponse.parse();
            Page page = pageRepository.findByPathAndSite(path, site).get();
            page.setPath(path);
            page.setContent(document.toString());
            page.setCode(connectionResponse.statusCode());
            pageRepository.save(page);
            if (page.getCode() < 400) {
                addRussianLemmas(document, site, page);
            }
            site.setStatusTime(LocalDateTime.now());
            siteRepository.save(site);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return null;
    }

    public synchronized void addRussianLemmas(Document document, Site site, Page page) throws IOException {

        RussianLemmaFinder russianLemmaFinder = new RussianLemmaFinder();

        HashMap<String, Integer> lemmas = russianLemmaFinder.collectLemmas(document.toString());

        for (String lemmaText : lemmas.keySet()) {
            if (IndexingServiceImpl.isCanceled()) {
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

    public static String getCorrectUrlFormat (String url) {
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

    public static Connection.Response getConnectionResponse(String url) throws IOException {
        return Jsoup.connect(url)
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .userAgent("Mozilla/5.0 (Windows; U; WindowsNT 5.1; en-US; rv1.8.1.6) Gecko/20070725 Firefox/2.0.0.6")
                .referrer("http://www.google.com")
                .execute();
    }

}

