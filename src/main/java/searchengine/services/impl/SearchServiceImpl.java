package searchengine.services.impl;

import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import searchengine.dto.indexing.RussianLemmaFinder;
import searchengine.dto.search.SearchData;
import searchengine.dto.search.SearchResponse;
import searchengine.model.Index;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;
import searchengine.services.SearchService;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {
    @Autowired
    private SiteRepository siteRepository;
    @Autowired
    private PageRepository pageRepository;
    @Autowired
    private LemmaRepository lemmaRepository;
    @Autowired
    private IndexRepository indexRepository;

    @Override
    public SearchResponse startSearch(String query, String url) {

        SearchResponse response = new SearchResponse();

        if (query.isBlank()) {
            response.setResult(false);
            response.setError("Задан пустой поисковый запрос");
            return response;
        }

        List<SearchData> data = searchData(query, url);
        response.setResult(true);
        response.setData(data);
        response.setCount(data.size());
        return response;
    }

    private List<SearchData> searchData (String query, String url) {
        List<SearchData> data = new ArrayList<>();

        List<Site> siteList = new ArrayList<>();
        if (url.isEmpty()) {
            siteList = siteRepository.findAll();
        } else {
            siteList.add(siteRepository.findByUrl(url).get());
        }

        RussianLemmaFinder russianLemmaFinder = createRussianLemmaFinder();
        for (Site site : siteList) {
            List<Lemma> queryLemmas = sortedExistingLemmaList(russianLemmaFinder.getLemmaSet(query), site);
            List<Page> pages = findPagesByLemmas(queryLemmas);
            for (Page page : pages) {
                data.add(PageData(page, site, query, queryLemmas));
            }
        }
        Collections.sort(data, (o1, o2) -> o2.getRelevance().compareTo(o1.getRelevance()));

        return data;
    }


    private List<Lemma> sortedExistingLemmaList(Set<String> lemmasSet, Site site) {
        List<Lemma> lemmas = new ArrayList<>();
        for (String lemma : lemmasSet) {
            if (lemmaRepository.findByTextAndSite(lemma, site).isPresent())
                lemmas.add(lemmaRepository.findByTextAndSite(lemma, site).get());
        }
        Collections.sort(lemmas, Comparator.comparing(Lemma::getFrequency));
        return lemmas;
    }

    private SearchData PageData(Page page, Site site, String query, List<Lemma> lemmas) {
        SearchData pageData = new SearchData();
        pageData.setSite(site.getUrl());
        pageData.setUri(page.getPath());
        pageData.setSiteName(site.getName());
        String html = page.getContent();
        Document document = Jsoup.parse(html);
        pageData.setTitle(document.title());
        pageData.setSnippet(addSnippet(html, query));
        pageData.setRelevance(indexRepository.findIndexRank(page, lemmas).stream().mapToInt(Integer::intValue).sum());
        return pageData;
    }

    private String addSnippet(String html, String query) {

        StringBuilder snippet = new StringBuilder();
        html = html.toLowerCase().replaceAll("([^а-я\\s])", " ").trim();
        String[] queryArray = query.trim().toLowerCase().split("\\s+");
        for (int i = 0; i < queryArray.length; i++) {
            String word = queryArray[i];
            int wordIndex = html.indexOf(word);
            if (wordIndex == -1) {
                continue;
            }
            int start = (wordIndex - 20) > 20 ? (wordIndex - 20) : wordIndex;
            int finish = start + 51 > html.length() ? html.length() - 1 : start + 50;

            snippet.append(" ... ");
            snippet.append(html, start, wordIndex);
            snippet.append("<b>");
            snippet.append(word);
            snippet.append("</b>");
            snippet.append(html, wordIndex + word.length(), finish);
        }
        snippet.append(" ... ");

        return snippet.toString();
    }

    private List<Page> findPagesByLemmas(List<Lemma> lemmas) {
        if (lemmas.isEmpty()) {
            return new ArrayList<>();
        }

        List<Page> pages = getPageList(lemmas.get(0));
        for (int i = 1; i < lemmas.size(); i++) {
            List<Page> temporaryPages = getPageList(lemmas.get(i));
            pages.retainAll(temporaryPages);
        }
        return pages;
    }

    private List<Page> getPageList(Lemma lemma) {
        return indexRepository.findAllByLemma(lemma)
                .stream()
                .map(Index::getPage)
                .collect(Collectors.toList());
    }

    private RussianLemmaFinder createRussianLemmaFinder() {
        try {
            return new RussianLemmaFinder();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
