package searchengine.services;

import searchengine.dto.indexing.IndexingResponse;

public interface IndexingService {

    IndexingResponse startIndexing();
    IndexingResponse stopIndexing();
    IndexingResponse onePageIndexing(String url);
}
