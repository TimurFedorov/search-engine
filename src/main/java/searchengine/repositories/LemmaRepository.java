package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import searchengine.model.Lemma;
import searchengine.model.Site;

import javax.transaction.Transactional;
import java.util.List;
import java.util.Optional;
@Repository
public interface LemmaRepository extends JpaRepository<Lemma,Long> {
    Optional<Lemma> findByTextAndSite(String lemma, Site site);
    List<Lemma> findAllBySite(Site site);
    @Transactional
    Integer deleteAllBySite (Site site);
}
