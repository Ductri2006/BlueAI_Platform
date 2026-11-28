package com.blueai.product;

import com.blueai.auth.model.UserRole;
import com.blueai.auth.exception.ApiException;
import com.blueai.auth.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {
    private final ProductCatalogService catalog;
    private final UserRepository users;
    private final BluecomProductClient upstream;
    public ProductController(ProductCatalogService catalog, UserRepository users, BluecomProductClient upstream) {
        this.catalog=catalog;this.users=users;this.upstream=upstream;
    }
    @GetMapping("/sandbox")
    public JsonNode sandbox(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="1") @Min(1) @Max(500) int page,
            @RequestParam(defaultValue="200") @Min(1) @Max(200) int size) {
        if (!isAdmin(jwt)) throw new ApiException(HttpStatus.FORBIDDEN,"PRODUCT_ADMIN_REQUIRED","Chỉ quản trị viên được đọc trực tiếp API đối tác.");
        return upstream.fetchPage(page,size);
    }
    @GetMapping
    public ProductCatalogService.PageResult list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="") @Size(max=200) String query,
            @RequestParam(required=false) @Pattern(regexp="[A-Za-z]{2}|!GL|!RG") String country,
            @RequestParam(required=false) @Min(1) @Max(3650) Integer days,
            @RequestParam(required=false) @DecimalMin("0") BigDecimal budget,
            @RequestParam(defaultValue="0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        return catalog.search(query,country,days,budget,page,size,isAdmin(jwt));
    }
    @GetMapping("/summary") public ProductCatalogService.Summary summary() { return catalog.summary(); }
    @GetMapping("/{sku}") public JsonNode detail(@AuthenticationPrincipal Jwt jwt,@PathVariable String sku) { return catalog.detail(sku,isAdmin(jwt)); }
    @PostMapping("/sync") public ProductCatalogService.Summary sync(@AuthenticationPrincipal Jwt jwt) {
        if (!isAdmin(jwt)) throw new ApiException(HttpStatus.FORBIDDEN,"PRODUCT_ADMIN_REQUIRED","Chỉ quản trị viên được đồng bộ danh mục.");
        return catalog.sync();
    }
    private boolean isAdmin(Jwt jwt) {
        return users.findById(jwt.getSubject()).map(user->user.getRole()==UserRole.ADMIN).orElse(false);
    }
}
