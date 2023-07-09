package searchengine.services.impl;

import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import searchengine.dto.search.SearchData;
import searchengine.dto.search.SearchResponse;
import searchengine.indexing.RussianLemmaFinder;
import searchengine.model.Index;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;
import searchengine.services.SearchService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


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

    private RussianLemmaFinder russianLemmaFinder;
    private final Logger logger = LogManager.getRootLogger();

    {
        try {
            russianLemmaFinder = new RussianLemmaFinder();
        } catch (IOException e) {
            logger.error(e.getMessage());;
        }
    }

    @Override
    public SearchResponse startSearch(String query, String url) {
        SearchResponse response = new SearchResponse();
        if (query.isBlank()) {
            response.setResult(false);
            response.setError("Задан пустой поисковый запрос");
            return response;
        }
        List<SearchData> data = getSearchData(query, url);
        response.setResult(true);
        response.setData(data);
        response.setCount(data.size());
        return response;
    }

    private List<SearchData> getSearchData(String query, String url) {
        List<SearchData> data = new ArrayList<>();
        List<Site> siteList = new ArrayList<>();
        if (url.isEmpty()) {
            siteList = siteRepository.findAll();
        } else {
            siteList.add(siteRepository.findByUrl(url).get());
        }
        for (Site site : siteList) {
            List<Lemma> queryLemmas = getSortedExistingLemmaList(russianLemmaFinder.getLemmaSet(query), site);
            List<Page> pages = findPagesByQueryLemmas(queryLemmas);
            for (Page page : pages) {
                data.add(getPageData(page, site, query, queryLemmas));
            }
        }
        Collections.sort(data, (o1, o2) -> o2.getRelevance().compareTo(o1.getRelevance()));
        return data;
    }


    private List<Lemma> getSortedExistingLemmaList(Set<String> lemmasSet, Site site) {
        List<Lemma> lemmas = new ArrayList<>();
        for (String lemma : lemmasSet) {
            if (lemmaRepository.findByTextAndSite(lemma, site).isPresent())
                lemmas.add(lemmaRepository.findByTextAndSite(lemma, site).get());
        }
        Collections.sort(lemmas, Comparator.comparing(Lemma::getFrequency));
        return lemmas;
    }

    private SearchData getPageData(Page page, Site site, String query, List<Lemma> lemmas) {
        SearchData pageData = new SearchData();
        pageData.setSite(site.getUrl());
        pageData.setUri(page.getPath());
        pageData.setSiteName(site.getName());
        String html = page.getContent();
        pageData.setTitle(Jsoup.parse(html).title());
        pageData.setSnippet(addSnippet(html, query));
        pageData.setRelevance(indexRepository.findIndexRank(page, lemmas).stream().mapToInt(Integer::intValue).sum());
        return pageData;
    }

    private String addSnippet(String html, String query) {
        List<String> queryArray = Arrays.stream(query.trim().toLowerCase().split("\\s+"))
                .filter(i -> !russianLemmaFinder.checkWordIsParticle(i)).toList();
        StringBuilder snippet = new StringBuilder();
        Document doc = Jsoup.parse(html);
        String description = addDescription(doc, queryArray);
        snippet.append(description.concat(" ... "));
        String keyWords = addKeyWords(doc,queryArray,snippet.length());
        snippet.append(keyWords);
        String additionalWords = addSomeWords(html,queryArray,snippet.length());
        snippet.append(additionalWords);
        return snippet.toString();
    }

    private List<Page> findPagesByQueryLemmas(List<Lemma> lemmas) {
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

    private String addDescription (Document doc, List<String> queryArray) {
        Elements elements = doc.getElementsByAttributeValue("name", "description");
        if (elements.isEmpty()) {
            return "";
        }
        String description = elements.get(0).attr("content");
        return markInBold(description, queryArray);
    }

    private String addKeyWords(Document doc, List<String> queryArray, int length) {
        StringBuilder keyWords = new StringBuilder();
        Elements elements = doc.select("a");
        for (Element e : elements) {
            if (length + keyWords.length() > 220) {
                break;
            }
            String text = e.text().toLowerCase();
            if (queryArray.stream().anyMatch(x -> text.contains(x))) {
                keyWords.append(markInBold(text, queryArray).concat(" ... "));
            }
        }
        return keyWords.toString();
    }

    private String addSomeWords (String html, List<String> queryArray, int length) {
        html = html.toLowerCase()
                .replaceAll("([^а-я\\s])", " ")
                .replaceAll("\\s+", " ").trim();
        StringBuilder someWords = new StringBuilder();
        for (int i = 0; i < queryArray.size(); i++) {
            if (length + someWords.length() > 220) {
                break;
            }
            String word = queryArray.get(i);
            int wordIndex = html.indexOf(word);

            if (wordIndex == -1) {
                continue;
            }
            int start = (wordIndex - 20) > 20 ? (wordIndex - 20) : wordIndex;
            int finish = start + 51 > html.length() ? html.length() - 1 : start + 50;
            someWords.append(html, start, wordIndex);
            someWords.append("<b>");
            someWords.append(word);
            someWords.append("</b>");
            someWords.append(html, wordIndex + word.length(), finish);
            someWords.append(" ... ");
        }
        return someWords.toString();
    }

    private String markInBold (String text, List<String> queryArray) {
        List<String> wordArray = new ArrayList<>();
        for (String s : (text.split("\\s+"))) {
            wordArray.add(s);
        }
        for (int i = 0; i < wordArray.size(); i++) {
            String word = wordArray.get(i);
            if (queryArray.stream().anyMatch(queryWord -> word.startsWith(queryWord))) {
                wordArray.set(i, "<b>".concat(word).concat("</b>"));
            }
        }
        return String.join(" ", wordArray);
    }

}
