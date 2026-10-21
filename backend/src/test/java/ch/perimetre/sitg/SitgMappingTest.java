package ch.perimetre.sitg;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import ch.perimetre.dossier.Groupe;

class SitgMappingTest {

    @ParameterizedTest
    @CsvSource({
            "ENREGISTREMENT, DEPOSE",
            "ENREGISTRE, DEPOSE",
            "INSTRUCTION, DEPOSE",
            "ACCEPTE, AUTORISE",
            "DECIDE, AUTORISE",
            "CHANTIER, CHANTIER",
            "RECOURS, RECOURS",
            "REFUSE, REFUSE",
            "REFUS ENTREE, REFUSE",
            "ABANDONNE, REFUSE",
            "CADUQUE, REFUSE",
            "AUTORISATION ANNULEE, REFUSE",
            "TERMINE, TERMINE",
            "ARCHIVE, TERMINE",
            "ARCHANT, TERMINE",
            "CLOTURE ADMIN, TERMINE"
    })
    void mapsKnownStatutToGroupe(String statut, Groupe expected) {
        assertThat(SitgMapping.groupe(statut)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"EN SUSPENS", "RENVOYE", "PROCEDURE AMS", "ORMIG", "-", "", "SOMETHING NEW"})
    void mapsEverythingElseToIgnore(String statut) {
        assertThat(SitgMapping.groupe(statut)).isEqualTo(Groupe.IGNORE);
    }

    @Test
    void nullDescriptionHasNoFlags() {
        assertThat(SitgMapping.flags(null)).isEmpty();
    }

    @Test
    void plainDescriptionHasNoFlags() {
        assertThat(SitgMapping.flags("transformation et réaménagement d'un restaurant au 4ème étage")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "Abattage de 3 arbres                         | arbres",
            "élagage d'un chêne                           | arbres",
            "construction de 12 LOGEMENTS                 | logements",
            "création d'un parking souterrain             | parking",
            "aménagement de places de stationnement       | parking",
            "construction d'une piscine                   | piscine",
            "installation de télécommunication mobile     | antenne",
            "remplacement d'une antenne                   | antenne",
            "Surélévation d'un immeuble                   | surelevation",
            "réalisation du PLQ 29'123                    | plq"
    })
    void detectsEachFlagCaseInsensitively(String description, String flag) {
        assertThat(SitgMapping.flags(description)).containsExactly(flag);
    }

    @Test
    void returnsAllMatchingFlagsInDeclaredOrder() {
        String description = "PLQ: construction de logements, piscine, parking et abattage d'arbres";
        assertThat(SitgMapping.flags(description))
                .isEqualTo(List.of("arbres", "logements", "parking", "piscine", "plq"));
    }

    @Test
    void matchesDecomposedAccents() {
        // "e" + combining acute accent, as sometimes produced by copy-paste
        assertThat(SitgMapping.flags("élagage et surélévation"))
                .containsExactly("arbres", "surelevation");
    }
}
