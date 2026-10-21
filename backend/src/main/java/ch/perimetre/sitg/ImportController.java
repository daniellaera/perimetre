package ch.perimetre.sitg;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

// TODO: bind to admin auth once security is in place.
@RestController
class ImportController {

    private final SitgImporter importer;

    ImportController(SitgImporter importer) {
        this.importer = importer;
    }

    @PostMapping("/admin/import")
    SitgImporter.ImportResult runImport() {
        return importer.run();
    }
}
