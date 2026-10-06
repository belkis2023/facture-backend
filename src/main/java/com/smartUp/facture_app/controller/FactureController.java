package com.smartUp.facture_app.controller;

import com.smartUp.facture_app.model.Facture;
import com.smartUp.facture_app.repository.FactureRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/factures")
@CrossOrigin(origins = "http://localhost:5173", allowedHeaders = "*")

public class FactureController {
    private final FactureRepository factureRepository;
    public FactureController(FactureRepository factureRepository) {
        this.factureRepository = factureRepository;
    }

    @GetMapping
    public List<Facture> getAll() {
        return factureRepository.findAll();
    }

    @PostMapping
    public Facture create(@RequestBody Facture facture) {
        return factureRepository.save(facture);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        Optional<Facture> facture = factureRepository.findById(id);
        return facture.map(f -> {
            String chemin = f.getCheminFichier();
            if (chemin != null && !chemin.isBlank()) {
                try {
                    Files.deleteIfExists(Paths.get(chemin));
                } catch (Exception e) {
                    log.warn("Could not delete file: {}", chemin, e);
                }
            }
            factureRepository.deleteById(id);
            return ResponseEntity.ok().build();
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public Facture update(@PathVariable Long id, @RequestBody Facture facture) {
        facture.setId(id);
        return factureRepository.save(facture);
    }


    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            System.out.println("UPLOAD ENDPOINT HIT");
            Path uploadPath = Paths.get("C:\\stages\\stage 3eme (smartUp)\\facture-app\\facture-backend\\uploads");
            //if the folder exists, this does nothing and doesn't throw any errors or anything
            Files.createDirectories(uploadPath);

            //destination for uploading a specific file
            //resolve = joining a folder path + filename
            Path destination = uploadPath.resolve(file.getOriginalFilename());

            //actually copying the file's contents to disk
            Facture facture = new Facture();
            facture.setNumeroFacture(null);
            facture.setFournisseur(null);
            facture.setMontantTTC(null);
            facture.setMontantHT(null);
            facture.setDateFacture(null);
            facture.setNomFichier(file.getOriginalFilename());
            facture.setCheminFichier(destination.toString());
            facture.setStatut("UPLOADED");
            facture.setDateUpload(LocalDateTime.now());

            Files.copy(file.getInputStream(), destination, StandardCopyOption.REPLACE_EXISTING);

            factureRepository.save(facture);

            return ResponseEntity.ok(new java.util.HashMap<String, String>() {{
                put("message", "File uploaded successfully, facture created in database");
                put("fileName", file.getOriginalFilename());
            }});
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Upload failed: " + e.getMessage());
        }
    }


    @PostMapping("/upload-multiple")
    public ResponseEntity<?> uploadMultipleFiles(@RequestParam("files") MultipartFile[] files) {
        try {
            Path uploadPath = Paths.get("C:\\stages\\stage 3eme (smartUp)\\facture-app\\facture-backend\\uploads");
            //if the folder exists, this does nothing and doesn't throw any errors or anything
            Files.createDirectories(uploadPath);

            List<String> uploadedFiles = new java.util.ArrayList<>();

            for (MultipartFile file: files) {
                if(file.isEmpty()) continue;
                //destination for uploading a specific file
                //resolve = joining a folder path + filename
                Path destination = uploadPath.resolve(file.getOriginalFilename());
                //actually copying the file's contents to disk
                Files.copy(file.getInputStream(), destination, StandardCopyOption.REPLACE_EXISTING);
                uploadedFiles.add(file.getOriginalFilename());
            }


            return ResponseEntity.ok(new java.util.HashMap<String, Object>() {{
                put("message", "Files uploaded successfully");
                put("count", uploadedFiles.size());
                put("files", uploadedFiles);
            }});
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Upload failed: " + e.getMessage());
        }
    }


    @GetMapping("/{id}/file")
    public ResponseEntity<?> getFileById(@PathVariable Long id) {
        return factureRepository.findById(id).map(f -> {
            String chemin = f.getCheminFichier();
            if (chemin == null || chemin.isBlank()) return ResponseEntity.notFound().build();
            try {
                Path file = Paths.get(chemin);
                if (!Files.exists(file)) return ResponseEntity.notFound().build();
                Resource resource = new UrlResource(file.toUri());
                String contentType = Files.probeContentType(file);
                MediaType mediaType = (contentType != null)
                        ? MediaType.parseMediaType(contentType)
                        : MediaType.APPLICATION_OCTET_STREAM;
                return ResponseEntity.ok()
                        .contentType(mediaType)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + f.getNomFichier() + "\"")
                        .body(resource);
            } catch (Exception e) {
                log.error("serve file error", e);
                return ResponseEntity.status(500).build();
            }
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

}
