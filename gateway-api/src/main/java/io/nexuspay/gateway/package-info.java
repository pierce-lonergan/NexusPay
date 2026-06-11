@org.springframework.modulith.ApplicationModule(
        // "fraud" added (B-003): the pre-authorization gate consults the fraud
        // assessment inbound port before the PSP call.
        allowedDependencies = {"common", "payment", "ledger", "iam", "fraud"}
)
package io.nexuspay.gateway;
