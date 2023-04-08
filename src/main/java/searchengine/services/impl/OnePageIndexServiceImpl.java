package searchengine.services.impl;

import lombok.RequiredArgsConstructor;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
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
import searchengine.services.OnePageIndexService;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;

@Service
@RequiredArgsConstructor
public class OnePageIndexServiceImpl implements OnePageIndexService {

    @Autowired
    private SiteRepository siteRepository;
    @Autowired
    private PageRepository pageRepository;
    @Autowired
    private IndexRepository indexRepository;
    @Autowired
    private LemmaRepository lemmaRepository;

    private final SitesList sites;

    private static RussianLemmaFinder russianLemmaFinder;

    static {
        try {
            russianLemmaFinder = new RussianLemmaFinder();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public IndexingResponse OnePageParser(String url) {

        url = IndexingServiceImpl.getCorrectUrlFormat(url);
        String siteUrl = null;
        String siteName = null;
        boolean siteIsInConfig = false;
        for (searchengine.config.Site siteCfg : sites.getSites()) {
            if (url.startsWith(IndexingServiceImpl.getCorrectUrlFormat(siteCfg.getUrl()))) {
                siteIsInConfig = true;
                siteUrl = IndexingServiceImpl.getCorrectUrlFormat(siteCfg.getUrl());
                siteName = siteCfg.getName();
            }
        }

        IndexingResponse response = new IndexingResponse();

        if (!siteIsInConfig) {
            response.setResult(false);
            response.setError("Данная страница находится за пределами сайтов, указанных в конфигурационном файле");
            return response;
        }

        Site site = addSite(url, siteUrl, siteName);
        addPage(site, url);

        response.setResult(true);
        return response;
    }

    private Site addSite (String url, String siteUrl, String siteName) {
        boolean siteWasParsed = false;
        Site site = null;
        for (Site s : siteRepository.findAll()) {
            if (url.startsWith(s.getUrl())) {
                siteWasParsed = true;
                site = s;
            }
        }

        if (!siteWasParsed) {
            site = new Site();
            site.setUrl(siteUrl);
            site.setName(siteName);
            site.setStatus(Status.INDEXED);
            site.setStatusTime(LocalDateTime.now());
            siteRepository.save(site);
        }
        return site;
    }



    private void addPage (Site site, String url) {
        try {
            Thread.sleep(150);
            Connection.Response connectionResponse = IndexingServiceImpl.getConnectionResponse(url);
            Document document = connectionResponse.parse();
            String path = url.substring(site.getUrl().length() - 1);
            Page page;
            if(!pageRepository.findByPathAndSite(path, site).isPresent()) {
                page = new Page();
                page.setSite(site);
            } else {
                page = pageRepository.findByPathAndSite(path, site).get();
            }
            page.setPath(path);
            page.setContent(document.toString());
            page.setCode(connectionResponse.statusCode());
            site.setStatusTime(LocalDateTime.now());
            pageRepository.save(page);
            siteRepository.save(site);

            addRussianLemmasAndIndexes(document, site, page);

        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void addRussianLemmasAndIndexes (Document document, Site site, Page page) {

        HashMap<String, Integer> lemmas = russianLemmaFinder.collectLemmas(document.toString());

        for (String lemmaText : lemmas.keySet()) {
            Lemma lemma;
            Index index = new Index();
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
            index.setPage(page);
            index.setLemma(lemma);
            index.setRank(lemmas.get(lemmaText));
            lemmaRepository.save(lemma);
            indexRepository.save(index);
        }
    }

}
