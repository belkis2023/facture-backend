package com.smartUp.facture_app.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "factures")
@Data
public class Facture {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


    @Column(name = "numero_facture")
    private String numeroFacture;

    private String fournisseur;
    private Double montantHT;
    private Double montantTTC;

    @Column(name = "nom_fichier")
    private String nomFichier;

    @Column(name = "date_facture")
    private String dateFacture;

    @Column(name = "chemin_fichier")
    private String cheminFichier;
    private String statut;

    @Column(name = "date_upload")
    private LocalDateTime dateUpload;

}
