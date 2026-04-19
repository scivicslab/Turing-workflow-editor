package com.scivicslab.workfloweditor.batch;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "batch_job")
@RegisterForReflection
public class BatchJob extends PanacheEntityBase {

    @Id
    public String id;

    public String name;

    @Lob
    @Column(columnDefinition = "CLOB")
    public String yaml;

    @Lob
    @Column(columnDefinition = "CLOB")
    public String parameters;

    public String status;

    public Instant createdAt;
    public Instant startedAt;
    public Instant finishedAt;
    public Integer exitCode;

    @Lob
    @Column(columnDefinition = "CLOB")
    public String log;
}
