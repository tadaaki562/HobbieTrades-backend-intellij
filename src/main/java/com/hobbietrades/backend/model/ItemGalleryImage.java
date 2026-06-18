package com.hobbietrades.backend.model;

import jakarta.persistence.*;

@Entity
@Table(name = "item_gallery_images",
        uniqueConstraints = @UniqueConstraint(columnNames = {"item_id", "slot"}))
public class ItemGalleryImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(nullable = false)
    private int slot;

    @Lob
    @Column(name = "image_data", nullable = false, columnDefinition = "LONGBLOB")
    private byte[] imageData;

    @Column(name = "mime_type", length = 64)
    private String mimeType;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getItemId() { return itemId; }
    public void setItemId(Long itemId) { this.itemId = itemId; }

    public int getSlot() { return slot; }
    public void setSlot(int slot) { this.slot = slot; }

    public byte[] getImageData() { return imageData; }
    public void setImageData(byte[] imageData) { this.imageData = imageData; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
}
