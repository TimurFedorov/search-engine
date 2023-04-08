package searchengine.services;

import searchengine.dto.indexing.IndexingResponse;

public interface OnePageIndexService {

    IndexingResponse OnePageParser(String url);
}
