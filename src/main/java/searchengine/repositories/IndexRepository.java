package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import searchengine.model.Index;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.Site;

import javax.transaction.Transactional;
import java.util.List;

public interface IndexRepository extends JpaRepository<Index, Integer> {
    @Transactional
    Integer deleteAllByPage(Page page);

    List<Index> findAllByLemma(Lemma lemma);

    @Query(value = "SELECT index_rank FROM search_engine.index_table \n" +
            "WHERE lemma_id IN :lemma_id AND page_id= :page_id", nativeQuery = true)
    List <Integer> findIndexRank(@Param("page_id") Page page, @Param("lemma_id") List<Lemma> lemmas);

}
