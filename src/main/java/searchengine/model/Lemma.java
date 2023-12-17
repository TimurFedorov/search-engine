package searchengine.model;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;

@Getter
@Setter
@Entity
public class Lemma {
    @Id
    @GeneratedValue (strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "site_id", nullable = false)
    private Site site;

    @Column(name = "text", columnDefinition = "VARCHAR(255) NOT NULL, KEY text_index(text(255))" )
    private String text;

    @Column(nullable = false)
    private Integer frequency;
}
