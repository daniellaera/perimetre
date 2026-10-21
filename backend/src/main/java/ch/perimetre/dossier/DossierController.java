package ch.perimetre.dossier;

import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DossierController {

    private final DossierRepository dossiers;

    DossierController(DossierRepository dossiers) {
        this.dossiers = dossiers;
    }

    @GetMapping("/dossiers")
    List<DossierView> within(
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double lng,
            @RequestParam @Positive @Max(2000) int radius) {
        return dossiers.findWithin(lat, lng, radius);
    }
}
