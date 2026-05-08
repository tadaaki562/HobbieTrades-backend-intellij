package com.hobbietrades.backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "trades")
public class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "proposer_id")
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"password"})
    private User proposer;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "receiver_id")
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"password"})
    private User receiver;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "offered_item_id")
    private Item offeredItem;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "requested_item_id")
    private Item requestedItem;

    private String status = "pending";

    @Column(name = "proposer_confirmed")
    private Boolean proposerConfirmed = false;

    @Column(name = "receiver_confirmed")
    private Boolean receiverConfirmed = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getProposer() { return proposer; }
    public void setProposer(User proposer) { this.proposer = proposer; }

    public User getReceiver() { return receiver; }
    public void setReceiver(User receiver) { this.receiver = receiver; }

    public Item getOfferedItem() { return offeredItem; }
    public void setOfferedItem(Item offeredItem) { this.offeredItem = offeredItem; }

    public Item getRequestedItem() { return requestedItem; }
    public void setRequestedItem(Item requestedItem) { this.requestedItem = requestedItem; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Boolean getProposerConfirmed() { return proposerConfirmed; }
    public void setProposerConfirmed(Boolean proposerConfirmed) { this.proposerConfirmed = proposerConfirmed; }

    public Boolean getReceiverConfirmed() { return receiverConfirmed; }
    public void setReceiverConfirmed(Boolean receiverConfirmed) { this.receiverConfirmed = receiverConfirmed; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}