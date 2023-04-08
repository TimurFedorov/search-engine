package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import searchengine.model.Page;
import searchengine.model.Site;

import javax.transaction.Transactional;
import java.util.List;
import java.util.Optional;

public interface PageRepository extends JpaRepository<Page,Integer> {
    Optional<Page> findByPathAndSite (String path, Site site);
    List<Page> findAllBySite(Site site);
    @Transactional
    Integer deleteAllBySite(Site site);
}
