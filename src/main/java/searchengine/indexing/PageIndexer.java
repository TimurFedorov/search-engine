package searchengine.indexing;

import lombok.AllArgsConstructor;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import searchengine.model.Site;
import searchengine.services.impl.IndexingServiceImpl;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RecursiveTask;

@AllArgsConstructor
public class PageIndexer extends RecursiveTask<String> {

    private SiteInformationAdder siteInformationAdder;
    private Site site;
    private String url;
    private CopyOnWriteArrayList<String> allPages;

    @Override
    protected String compute() {

        StringBuilder stringBuilder = new StringBuilder(url + "\n");
        List<PageIndexer> pageIndexers = new CopyOnWriteArrayList<>();

        Document document = siteInformationAdder.addPage(site, url);
        Elements elements = document.select("a[href]");
        for (Element element : elements) {
            if (IndexingServiceImpl.isCanceled()) {
                break;
            }
            String attributeUrl = SiteInformationAdder.getCorrectUrlFormat(element.absUrl("href"));
            if ((attributeUrl.startsWith(url))
                    && !attributeUrl.contains("#")
                    && !allPages.contains(attributeUrl)) {
                PageIndexer links = new PageIndexer(siteInformationAdder, site, attributeUrl, allPages);
                links.fork();
                pageIndexers.add(links);
                allPages.add(attributeUrl);
            }
        }

        pageIndexers.sort(Comparator.comparing((PageIndexer o) -> o.url));
        for (PageIndexer link : pageIndexers) {
            stringBuilder.append(link.join());
        }
        return stringBuilder.toString();
    }
}

