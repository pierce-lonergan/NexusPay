package io.nexuspay.billing.adapter.in.rest;

import io.nexuspay.billing.application.port.out.ProductRepository;
import io.nexuspay.billing.domain.Price;
import io.nexuspay.billing.domain.PricingModel;
import io.nexuspay.billing.domain.Product;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for products and prices.
 *
 * @since 0.2.5 (Sprint 2.5a)
 */
@RestController
@RequestMapping("/v1")
public class ProductController {

    private final ProductRepository productRepository;

    public ProductController(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @PostMapping("/products")
    public ResponseEntity<ProductResponse> createProduct(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestBody CreateProductRequest request) {

        Product product = Product.create(tenantId, request.name(), request.description(), request.metadata());
        product = productRepository.saveProduct(product);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(product));
    }

    @GetMapping("/products")
    public ResponseEntity<List<ProductResponse>> listProducts(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        return ResponseEntity.ok(productRepository.findProductsByTenant(tenantId, limit, offset)
                .stream().map(this::toResponse).toList());
    }

    @PostMapping("/prices")
    public ResponseEntity<PriceResponse> createPrice(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestBody CreatePriceRequest request) {

        Price price = switch (PricingModel.valueOf(request.pricingModel().toUpperCase())) {
            case FLAT -> Price.createFlat(request.productId(), tenantId, request.currency(),
                    request.unitAmount(), request.billingInterval(), request.billingIntervalCount(),
                    request.trialDays());
            case PER_UNIT -> Price.createPerUnit(request.productId(), tenantId, request.currency(),
                    request.unitAmount(), request.billingInterval(), request.billingIntervalCount(),
                    request.trialDays());
            case TIERED, VOLUME, PACKAGE -> Price.createTiered(request.productId(), tenantId,
                    request.currency(), request.tiers(), request.billingInterval(),
                    request.billingIntervalCount(), request.trialDays());
        };

        price = productRepository.savePrice(price);
        return ResponseEntity.status(HttpStatus.CREATED).body(toPriceResponse(price));
    }

    @GetMapping("/prices")
    public ResponseEntity<List<PriceResponse>> listPrices(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        return ResponseEntity.ok(productRepository.findPricesByTenant(tenantId, limit, offset)
                .stream().map(this::toPriceResponse).toList());
    }

    // ---- DTOs ----

    private ProductResponse toResponse(Product p) {
        return new ProductResponse(p.getId(), p.getName(), p.getDescription(), p.isActive(),
                p.getCreatedAt().toString());
    }

    private PriceResponse toPriceResponse(Price p) {
        return new PriceResponse(p.getId(), p.getProductId(), p.getCurrency(),
                p.getPricingModel().name(), p.getUnitAmount(),
                p.getBillingInterval(), p.getBillingIntervalCount(),
                p.getTrialDays(), p.isActive(), p.getCreatedAt().toString());
    }

    record CreateProductRequest(String name, String description, Map<String, Object> metadata) {}

    record CreatePriceRequest(String productId, String currency, String pricingModel,
                               long unitAmount, List<Map<String, Object>> tiers,
                               String billingInterval, int billingIntervalCount, int trialDays) {}

    record ProductResponse(String id, String name, String description, boolean active, String createdAt) {}

    record PriceResponse(String id, String productId, String currency, String pricingModel,
                          Long unitAmount, String billingInterval, int billingIntervalCount,
                          int trialDays, boolean active, String createdAt) {}
}
