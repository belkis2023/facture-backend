package com.smartUp.facture_app.repository;

import com.smartUp.facture_app.model.Facture;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FactureRepository extends JpaRepository<Facture, Long> {

}
