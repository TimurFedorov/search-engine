package searchengine.services.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.SitesList;
import searchengine.dto.statistics.DetailedStatisticsItem;
import searchengine.dto.statistics.StatisticsData;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.dto.statistics.TotalStatistics;
import searchengine.indexing.SiteInformationAdder;
import searchengine.model.Site;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;
import searchengine.services.StatisticsService;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {

    @Autowired
    private SiteRepository siteRepository;
    @Autowired
    private PageRepository pageRepository;
    @Autowired
    private LemmaRepository lemmaRepository;
    private final SitesList sites;
    private int siteCount;

    @Override
    public StatisticsResponse getStatistics() {
        siteCount = sites.getSites().size();
        StatisticsData data = new StatisticsData();
        data.setTotal(getTotal());
        data.setDetailed(getDetailed());
        StatisticsResponse response = new StatisticsResponse();
        response.setStatistics(data);
        response.setResult(true);
        return response;
    }

    @Transactional(readOnly = true)
    public TotalStatistics getTotal() {
        TotalStatistics total = new TotalStatistics();
        total.setSites(siteCount);
        total.setIndexing(IndexingServiceImpl.checkThreadIsAlive());
        total.setPages((int) pageRepository.count());
        total.setLemmas((int) lemmaRepository.count());
        return total;
    }

    private List<DetailedStatisticsItem> getDetailed() {
        List<DetailedStatisticsItem> detailed = new ArrayList<>();
        for (int i = 0; i < siteCount; i++) {
            String name = sites.getSites().get(i).getName();
            String url = SiteInformationAdder.getCorrectUrlFormat(sites.getSites().get(i).getUrl());
            detailed.add(getItem(name, url));
        }
        return detailed;
    }

    @Transactional(readOnly = true)
    public DetailedStatisticsItem getItem(String name, String url) {
        DetailedStatisticsItem item = new DetailedStatisticsItem();
        item.setName(name);
        item.setUrl(url);
        if (siteRepository.findByUrl(url).isPresent()) {
            Site site = siteRepository.findByUrl(url).get();
            item.setPages(pageRepository.countBySite(site));
            item.setLemmas(lemmaRepository.countBySite(site));
            item.setStatus(site.getStatus().toString());
            item.setError(site.getLastError() == null ? "" : site.getLastError());
            item.setStatusTime(ZonedDateTime.of(site.getStatusTime(), ZoneId.systemDefault()).toInstant().toEpochMilli());
            return item;
        }
        item.setPages(0);
        item.setLemmas(0);
        item.setStatus("NOT INDEXED");
        item.setError("");
        item.setStatusTime(System.currentTimeMillis());
        return item;
    }

}
