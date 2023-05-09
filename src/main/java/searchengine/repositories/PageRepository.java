package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import searchengine.model.Page;
import searchengine.model.Site;

import javax.transaction.Transactional;
import java.util.List;
import java.util.Optional;

@Repository
public interface PageRepository extends JpaRepository<Page,Long> {
    Optional<Page> findByPathAndSite (String path, Site site);
    List<Page> findAllBySite(Site site);
    @Transactional
    Integer deleteAllBySite(Site site);
}
