package searchengine.dto.search;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

@Data
public class SearchResponse {
    private boolean result;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String error;
    private int count;
    private List<SearchData> data;
}


