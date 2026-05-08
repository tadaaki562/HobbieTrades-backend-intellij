package com.hobbietrades.backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "reviews")
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The user writing the review
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "reviewer_id")
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"password"})
    private User reviewer;

    // The user being reviewed
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "reviewee_id")
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"password"})
    private User reviewee;

    // The trade this review belongs to
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "trade_id")
    private Trade trade;

    @Column(name = "overall_rating", nullable = false)
    private Integer overallRating;

    @Column(name = "item_as_described")
    private Integer itemAsDescribed;

    @Column(name = "communication")
    private Integer communication;

    @Column(name = "meetup_reliability")
    private Integer meetupReliability;

    @Column(name = "review_text", columnDefinition = "TEXT")
    private String reviewText;

    // Comma-separated quick tags e.g. "Honest,Punctual,Would trade again"
    @Column(name = "tags")
    private String tags;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getReviewer() {
        return reviewer;
    }

    public void setReviewer(User reviewer) {
        this.reviewer = reviewer;
    }

    public User getReviewee() {
        return reviewee;
    }

    public void setReviewee(User reviewee) {
        this.reviewee = reviewee;
    }

    public Trade getTrade() {
        return trade;
    }

    public void setTrade(Trade trade) {
        this.trade = trade;
    }

    public Integer getOverallRating() {
        return overallRating;
    }

    public void setOverallRating(Integer overallRating) {
        this.overallRating = overallRating;
    }

    public Integer getItemAsDescribed() {
        return itemAsDescribed;
    }

    public void setItemAsDescribed(Integer itemAsDescribed) {
        this.itemAsDescribed = itemAsDescribed;
    }

    public Integer getCommunication() {
        return communication;
    }

    public void setCommunication(Integer communication) {
        this.communication = communication;
    }

    public Integer getMeetupReliability() {
        return meetupReliability;
    }

    public void setMeetupReliability(Integer meetupReliability) {
        this.meetupReliability = meetupReliability;
    }

    public String getReviewText() {
        return reviewText;
    }

    public void setReviewText(String reviewText) {
        this.reviewText = reviewText;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}