/**
 * Identity and access management bounded context — API keys, RBAC,
 * maker-checker approvals, audit logging, session tokens.
 *
 * <p>Declared {@code OPEN} (transitional): gateway consumes
 * {@code NexusPayPrincipal}, {@code ApprovalService}, and the session-token
 * services from {@code domain}/{@code application}, which Modulith's default
 * base-package-only exposure would flag as internal access. The long-term fix
 * is {@code @NamedInterface} declarations on those packages.</p>
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        allowedDependencies = {"common"}
)
package io.nexuspay.iam;
