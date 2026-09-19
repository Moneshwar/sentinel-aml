package com.moneshwar.hackathon.entity;
import jakarta.persistence.*;
import lombok.*;
@Entity @Table(name="sanctioned_counterparties") @Getter @Setter @NoArgsConstructor
public class SanctionedCounterparty {
    @Id @Column(length=255) private String identifier;
    @Column(length=500) private String description;
    @Column(nullable=false) private boolean enabled=true;
}
