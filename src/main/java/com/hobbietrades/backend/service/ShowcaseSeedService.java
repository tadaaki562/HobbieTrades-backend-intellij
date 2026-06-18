package com.hobbietrades.backend.service;

import com.hobbietrades.backend.model.Item;
import com.hobbietrades.backend.model.User;
import com.hobbietrades.backend.repository.ItemRepository;
import com.hobbietrades.backend.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Seeds realistic Philippines showcase accounts and listings (idempotent).
 */
@Service
public class ShowcaseSeedService {

    public static final String DEMO_PASSWORD = "HobbieDemo2026!";

    private final UserRepository userRepository;
    private final ItemRepository itemRepository;
    private final PasswordEncoder passwordEncoder;

    public ShowcaseSeedService(
            UserRepository userRepository,
            ItemRepository itemRepository,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.itemRepository = itemRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public Map<String, Object> seed() {
        List<Map<String, Object>> usersCreated = new ArrayList<>();
        List<Map<String, Object>> listingsCreated = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (DemoUser demo : DEMO_USERS) {
            User user = ensureUser(demo, usersCreated, skipped);
            for (DemoListing listing : demo.listings()) {
                ensureListing(user, listing, listingsCreated, skipped);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Showcase seed complete.");
        result.put("demoPassword", DEMO_PASSWORD);
        result.put("usersCreated", usersCreated.size());
        result.put("listingsCreated", listingsCreated.size());
        result.put("users", usersCreated);
        result.put("listings", listingsCreated);
        result.put("skipped", skipped);
        return result;
    }

    private User ensureUser(DemoUser demo, List<Map<String, Object>> created, List<String> skipped) {
        Optional<User> existing = userRepository.findByEmail(demo.email());
        if (existing.isPresent()) {
            User user = existing.get();
            skipped.add("user exists: " + demo.email());
            return user;
        }

        User user = new User();
        user.setName(demo.name());
        user.setEmail(demo.email());
        user.setPassword(passwordEncoder.encode(DEMO_PASSWORD));
        user.setLocation(demo.location());
        user.setRating(demo.rating());
        user.setTradeCount(demo.tradeCount());
        userRepository.save(user);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", user.getId());
        row.put("name", user.getName());
        row.put("email", user.getEmail());
        row.put("location", user.getLocation());
        created.add(row);
        return user;
    }

    private void ensureListing(User user, DemoListing demo, List<Map<String, Object>> created, List<String> skipped) {
        boolean exists = itemRepository.findByUserId(user.getId()).stream()
                .anyMatch(i -> demo.title().equalsIgnoreCase(i.getTitle()));
        if (exists) {
            skipped.add("listing exists: " + demo.title());
            return;
        }

        Item item = new Item();
        item.setUser(user);
        item.setTitle(demo.title());
        item.setDescription(demo.description());
        item.setCategory(demo.category());
        item.setConditionLabel(demo.condition());
        item.setEstimatedValue(BigDecimal.valueOf(demo.valuePhp()));
        item.setLookingFor(demo.lookingFor());
        item.setLocation(demo.location() != null ? demo.location() : user.getLocation());
        item.setPhotoUrl(demo.photoUrl());
        item.setIsAvailable(true);
        itemRepository.save(item);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", item.getId());
        row.put("title", item.getTitle());
        row.put("userId", user.getId());
        row.put("userName", user.getName());
        created.add(row);
    }

    private record DemoUser(
            String name,
            String email,
            String location,
            double rating,
            int tradeCount,
            List<DemoListing> listings) {}

    private record DemoListing(
            String title,
            String description,
            String category,
            String condition,
            long valuePhp,
            String lookingFor,
            String location,
            String photoUrl) {}

    private static final List<DemoUser> DEMO_USERS = List.of(
            new DemoUser(
                    "Miguel Reyes",
                    "miguel.reyes@hobbietrades.ph",
                    "Quezon City, Metro Manila",
                    4.8,
                    12,
                    List.of(
                            new DemoListing(
                                    "Canon EOS M50 Mark II Body Only",
                                    "Selling my Canon M50 Mark II body only — no lens. Bought from Henry's Cameras SM North 2022. "
                                            + "Shutter count around 8,500. Includes original battery, charger, and box. "
                                            + "Minor scuff on bottom plate from tripod use. Works perfectly for vlogging and stills. "
                                            + "Open to meetup around Trinoma / SM North or ship via LBC (COD available).",
                                    "Cameras",
                                    "Good",
                                    18500,
                                    "Acoustic guitar, mirrorless lens (EF-M or adapter), or drum hardware",
                                    null,
                                    "https://images.unsplash.com/photo-1516035069371-29a1b244cc32?w=800&q=80"),
                            new DemoListing(
                                    "Epiphone Les Paul Standard Plus Top Pro",
                                    "Epiphone Les Paul in Heritage Cherry Sunburst. Purchased from JB Music Glorietta. "
                                            + "Upgraded tuners to Grover. Frets still have plenty of life but shows buckle rash on back. "
                                            + "Comes with soft case. Ideal for gigging around Metro Manila — sounds great through a small amp.",
                                    "Instruments",
                                    "Fair",
                                    11800,
                                    "Mirrorless camera body, Sony or Fujifilm, or digital piano",
                                    null,
                                    "https://images.unsplash.com/photo-1493225457124-a3eb161ffa5f?w=800&q=80")
                    ),
            new DemoUser(
                    "Angelica Cruz",
                    "angelica.cruz@hobbietrades.ph",
                    "Makati City, Metro Manila",
                    4.6,
                    8,
                    List.of(
                            new DemoListing(
                                    "Yamaha F310 Acoustic Guitar",
                                    "Classic beginner-friendly Yamaha F310 in natural finish. Bought for org practice in college, "
                                            + "lightly used. Strings recently changed (D'Addario). Small ding near input jack area from stand. "
                                            + "Includes gig bag. Prefer meetup at Ayala MRT / Greenbelt area after office hours.",
                                    "Instruments",
                                    "Good",
                                    6200,
                                    "Entry-level mirrorless camera or GoPro for travel content",
                                    null,
                                    "https://images.unsplash.com/photo-1510915361893-db8efb743146?w=800&q=80")
                    ),
            new DemoUser(
                    "Carlo Mendoza",
                    "carlo.mendoza@hobbietrades.ph",
                    "Cebu City, Cebu",
                    4.9,
                    15,
                    List.of(
                            new DemoListing(
                                    "Sony Alpha a6000 with 16-50mm Kit Lens",
                                    "Sony a6000 kit setup — my backup travel camera. Bought second-hand from a fellow shooter in IT Park. "
                                            + "Sensor is clean, autofocus snappy. Rubber grip slightly worn. Around 22k shutter count. "
                                            + "Includes kit lens, strap, and two batteries. Can meet at SM Seaside or Ayala Center Cebu.",
                                    "Cameras",
                                    "Fair",
                                    21500,
                                    "Electric guitar, studio monitors, or cymbal pack",
                                    null,
                                    "https://images.unsplash.com/photo-1502920917128-1aa500764cbd?w=800&q=80"),
                            new DemoListing(
                                    "Pearl Export Series 5-Piece Drum Kit (Wine Red)",
                                    "Pearl Export kit complete with hardware (no cymbals). Used for church gigs and practice room sessions. "
                                            + "Heads replaced last year. Some rack tom mounting wear. Breakdown fits in a sedan with seats down. "
                                            + "Pickup preferred in Cebu City; not shipping to Luzon due to size.",
                                    "Instruments",
                                    "Good",
                                    27500,
                                    "Mirrorless camera for band coverage, or lens 50mm f/1.8",
                                    null,
                                    "https://images.unsplash.com/photo-1519899300227-28d752280af4?w=800&q=80")
                    ),
            new DemoUser(
                    "Patricia Santos",
                    "patricia.santos@hobbietrades.ph",
                    "Pasig City, Metro Manila",
                    4.7,
                    6,
                    List.of(
                            new DemoListing(
                                    "Fujifilm X-T30 II with 15-45mm Kit Lens",
                                    "Fuji X-T30 II in silver — barely used, mostly for weekend street photography around BGC and Pasig. "
                                            + "Shutter count under 3,000. Like-new condition with screen protector since day one. "
                                            + "Complete box, strap, manual, and 64GB SD card. Film simulations are amazing for content creators.",
                                    "Cameras",
                                    "Like New",
                                    42500,
                                    "Digital piano 88-key, acoustic guitar, or telephoto lens",
                                    null,
                                    "https://images.unsplash.com/photo-1519184378650-f1010a4e368?w=800&q=80"),
                            new DemoListing(
                                    "Roland FP-30X Digital Piano (White)",
                                    "Roland FP-30X 88-key weighted action piano in white finish. Bought during pandemic for online lessons. "
                                            + "Includes original stand, pedal, and bench. Keys feel close to acoustic — great for condo living in Ortigas. "
                                            + "Buyer arranges pickup; elevator fits in my building. No trades for bulky drum kits please.",
                                    "Instruments",
                                    "Like New",
                                    46500,
                                    "Mirrorless camera setup, guitar, or camera lens",
                                    null,
                                    "https://images.unsplash.com/photo-1520523839897-bd0b52f945a0?w=800&q=80")
                    ),
            new DemoUser(
                    "James Tan",
                    "james.tan@hobbietrades.ph",
                    "Baguio City, Benguet",
                    4.5,
                    4,
                    List.of(
                            new DemoListing(
                                    "Nikon D3500 DSLR Kit (18-55mm VR)",
                                    "Nikon D3500 starter kit — perfect for tourism and family shoots here in Baguio. "
                                            + "Purchased at Octagon SM Baguio. Includes kit lens, bag, and 32GB card. "
                                            + "Some paint wear on grip from cold-weather shooting. Fully working, battery holds well.",
                                    "Cameras",
                                    "Good",
                                    17200,
                                    "Violin upgrade, acoustic guitar, or portable recorder",
                                    null,
                                    "https://images.unsplash.com/photo-1510127034890-ba275a750720?w=800&q=80"),
                            new DemoListing(
                                    "LYCOM Full Size Violin 4/4 with Case & Bow",
                                    "Student violin set from LYCOM Music Shop Session Road. Used for 2 semesters at Saint Louis University. "
                                            + "Rosin included, strings still decent. Case has travel stickers from Benguet bus trips. "
                                            + "Fair cosmetic condition — good for beginner continuing lessons.",
                                    "Instruments",
                                    "Fair",
                                    4200,
                                    "Mirrorless camera, guitar, or action camera",
                                    null,
                                    "https://images.unsplash.com/photo-1612221336002-de0b6e140579?w=800&q=80")
                    ),
            new DemoUser(
                    "Rico Dela Cruz",
                    "rico.delacruz@hobbietrades.ph",
                    "Davao City, Davao del Sur",
                    4.8,
                    10,
                    List.of(
                            new DemoListing(
                                    "GoPro Hero 9 Black with Accessories",
                                    "GoPro Hero 9 for motovlog and beach trips around Samal. Includes skeleton case, 3 batteries, "
                                            + "dual charger, and chest mount. Lens has light scratches but footage still sharp for social media. "
                                            + "Meetup at Abreeza Mall or Ecoland terminal area.",
                                    "Cameras",
                                    "Worn",
                                    8200,
                                    "Electric guitar, ukulele bundle, or studio mic",
                                    null,
                                    "https://images.unsplash.com/photo-1526170375885-4d8ecf77b99f?w=800&q=80"),
                            new DemoListing(
                                    "Fender Player Stratocaster (Made in Mexico)",
                                    "Fender Player Strat in Polar White. Bought from Guitar Master Davao. Upgraded pickguard to mint green. "
                                            + "Setup done last month — low action, noiseless on single coils at practice volume. "
                                            + "Comes with hard case. Looking for fair trades with camera gear or cash top-up.",
                                    "Instruments",
                                    "Good",
                                    33500,
                                    "Sony or Canon mirrorless, telephoto lens, or drum machine",
                                    null,
                                    "https://images.unsplash.com/photo-1550291650-8571d7a1a488?w=800&q=80")
                    )
    );
}
