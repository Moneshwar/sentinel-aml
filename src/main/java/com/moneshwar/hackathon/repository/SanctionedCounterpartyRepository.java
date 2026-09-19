package com.moneshwar.hackathon.repository;
import com.moneshwar.hackathon.entity.SanctionedCounterparty;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface SanctionedCounterpartyRepository extends JpaRepository<SanctionedCounterparty,String> {
    List<SanctionedCounterparty> findAllByEnabledTrue();
}
