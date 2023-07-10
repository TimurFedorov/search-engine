package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import searchengine.model.Index;
import searchengine.model.Lemma;
import searchengine.model.Page;

import java.util.List;

@Repository
public interface IndexRepository extends JpaRepository<Index, Long> {

    List<Index> findAllByLemma(Lemma lemma);

    @Query(value = "SELECT index_rank FROM search_engine.index_table \n" +
            "WHERE lemma_id IN :lemma_id AND page_id= :page_id", nativeQuery = true)
    List <Integer> findIndexRank(@Param("page_id") Page page, @Param("lemma_id") List<Lemma> lemmas);

}
