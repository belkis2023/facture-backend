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
import java.util.List;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
    public ResponseEntity<?> create(@RequestBody Facture facture) {
        String numeroFacture = normalizeInvoiceNumber(facture.getNumeroFacture());
        if (numeroFacture != null && factureRepository.existsByNumeroFactureIgnoreCase(numeroFacture)) {
            return duplicateInvoiceResponse(numeroFacture);
        }
        facture.setNumeroFacture(numeroFacture);
        return ResponseEntity.ok(factureRepository.save(facture));
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
    public ResponseEntity<Facture> update(@PathVariable Long id, @RequestBody Facture facture) {
        return factureRepository.findById(id).map(existing -> {
            String numeroFacture = normalizeInvoiceNumber(facture.getNumeroFacture());
            if (numeroFacture != null
                    && factureRepository.existsByNumeroFactureIgnoreCaseAndIdNot(numeroFacture, id)) {
                return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT)
                        .<Facture>build();
            }
            existing.setNumeroFacture(numeroFacture);
            existing.setFournisseur(facture.getFournisseur());
            existing.setMontantHT(facture.getMontantHT());
            existing.setMontantTTC(facture.getMontantTTC());
            existing.setDateFacture(facture.getDateFacture());
            existing.setStatut("VALIDATED");
            return ResponseEntity.ok(factureRepository.save(existing));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }


    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(@RequestParam("file") MultipartFile file) {
        Path destination = null;
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("Please select a non-empty invoice file.");
            }
            String extension = Optional.ofNullable(file.getOriginalFilename())
                    .map(name -> name.toLowerCase())
                    .filter(name -> name.contains("."))
                    .map(name -> name.substring(name.lastIndexOf('.') + 1))
                    .orElse("");
            if (!List.of("jpg", "jpeg", "png", "bmp", "tif", "tiff", "webp").contains(extension)) {
                return ResponseEntity.badRequest().body("Upload an image file (JPG, PNG, BMP, TIFF, or WEBP).");
            }

            Path uploadPath = Paths.get("uploads").toAbsolutePath().normalize();
            Files.createDirectories(uploadPath);

            String originalFilename = Optional.ofNullable(file.getOriginalFilename())
                    .map(name -> name.replace('\\', '/'))
                    .map(name -> Paths.get(name).getFileName().toString())
                    .filter(name -> !name.isBlank())
                    .orElse("invoice");
            destination = uploadPath.resolve(UUID.randomUUID() + "-" + originalFilename).normalize();
            if (!destination.startsWith(uploadPath)) {
                return ResponseEntity.badRequest().body("Invalid file name.");
            }

            Files.copy(file.getInputStream(), destination, StandardCopyOption.REPLACE_EXISTING);
            String[] extracted = extractInvoiceFields(destination);
            String numeroFacture = normalizeInvoiceNumber(textOrNull(extracted, 0));
            if (numeroFacture != null && factureRepository.existsByNumeroFactureIgnoreCase(numeroFacture)) {
                Files.deleteIfExists(destination);
                destination = null;
                return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT)
                        .body(java.util.Map.of(
                                "message",
                                "Une facture avec le numéro " + numeroFacture + " existe déjà."));
            }

            Facture facture = new Facture();
            facture.setNumeroFacture(numeroFacture);
            facture.setFournisseur(textOrNull(extracted, 1));
            facture.setDateFacture(textOrNull(extracted, 2));
            facture.setMontantHT(amountOrNull(extracted, 3));
            facture.setMontantTTC(amountOrNull(extracted, 4));
            facture.setNomFichier(originalFilename);
            facture.setCheminFichier(destination.toString());
            facture.setStatut("NEEDS_REVIEW");
            facture.setDateUpload(LocalDateTime.now());
            return ResponseEntity.ok(factureRepository.save(facture));
        } catch (Exception e) {
            if (destination != null) {
                try {
                    Files.deleteIfExists(destination);
                } catch (Exception cleanupError) {
                    log.warn("Could not remove failed upload: {}", destination, cleanupError);
                }
            }
            log.error("Invoice upload or extraction failed", e);
            return ResponseEntity.status(500).body("Upload or invoice extraction failed: " + e.getMessage());
        }
    }

    private String[] extractInvoiceFields(Path imagePath) throws Exception {
        Path workingDirectory = Paths.get("").toAbsolutePath().normalize();
        Path pythonDirectory = List.of(
                        workingDirectory.resolve("facture-python"),
                        workingDirectory.resolve("..").resolve("facture-python").normalize(),
                        workingDirectory.resolve("facture-app")
                                .resolve("facture-python").normalize())
                .stream()
                .filter(directory -> Files.isRegularFile(directory.resolve("extraction.py")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Could not find facture-python/extraction.py from " + workingDirectory));
        Path script = pythonDirectory.resolve("extraction.py");

        String python = Optional.ofNullable(System.getenv("PYTHON_EXECUTABLE"))
                .filter(value -> !value.isBlank())
                .orElseGet(() -> {
                    Path expectedPython = pythonDirectory.resolve(".venv")
                            .resolve("Scripts").resolve("python.exe");
                    return Files.isExecutable(expectedPython) ? expectedPython.toString() : "python";
                });
        Path processOutput = Files.createTempFile("invoice-extraction-", ".log");
        try {
            Process process = new ProcessBuilder(
                    python, script.toString(), "--image", imagePath.toString())
                    .directory(pythonDirectory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(processOutput.toFile())
                    .start();

            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("Invoice OCR timed out.");
            }

            String output = Files.readString(processOutput);
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Invoice OCR failed: " + output);
            }

            String resultLine = output.lines()
                    .filter(line -> line.split("\t", -1).length == 5)
                    .reduce((first, last) -> last)
                    .orElseThrow(() -> new IllegalStateException("OCR returned no extracted fields: " + output));
            return resultLine.split("\t", -1);
        } finally {
            Files.deleteIfExists(processOutput);
        }
    }

    private String textOrNull(String[] fields, int index) {
        return fields[index].isBlank() ? null : fields[index];
    }

    private String normalizeInvoiceNumber(String numeroFacture) {
        if (numeroFacture == null || numeroFacture.isBlank()) {
            return null;
        }
        return numeroFacture.trim();
    }

    private ResponseEntity<?> duplicateInvoiceResponse(String numeroFacture) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT)
                .body(java.util.Map.of(
                        "message",
                        "Une facture avec le numéro " + numeroFacture + " existe déjà."));
    }

    private Double amountOrNull(String[] fields, int index) {
        String value = textOrNull(fields, index);
        return value == null ? null : Double.valueOf(value);
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
