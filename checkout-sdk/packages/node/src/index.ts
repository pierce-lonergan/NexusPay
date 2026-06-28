/** @nexus-pay/node — typed NexusPay server SDK (payments client + webhook verification). */

export { NexusPay } from './client';
export type { NexusPayOptions } from './client';

export {
  verifyWebhook,
  constructEvent,
  generateTestHeaderString,
  generateTestSignature,
} from './webhooks';
export type {
  ConstructEventOptions,
  GenerateTestHeaderOptions,
  GenerateTestHeaderResult,
  TestWebhookHeaders,
} from './webhooks';

// TEST-5 (E2): typed sample webhook events for handler unit tests.
export { testFixtures, buildTestEvent } from './fixtures';
export type { TestEventOverrides } from './fixtures';

// TEST-5 (E4): a typed fake HTTP transport for the injectable `fetch` seam.
export { createTestTransport } from './testing';
export type {
  TestTransport,
  TestTransportHandler,
  TestTransportHandlers,
  TestTransportRequest,
  TestTransportResponseSpec,
  CreateTestTransportOptions,
  RecordedCall,
} from './testing';

export { NexusPayError, SignatureVerificationError } from './errors';
export type {
  NexusPayErrorType,
  ApiErrorBody,
  SignatureVerificationCode,
} from './errors';

export { WEBHOOK_EVENT_TYPES } from './types';
export type {
  CaptureMethod,
  Metadata,
  RequestOptions,
  CreatePaymentSessionParams,
  PaymentSession,
  CreatePaymentParams,
  Payment,
  ConfirmPaymentParams,
  CapturePaymentParams,
  CancelPaymentParams,
  CreateRefundParams,
  Refund,
  RefundApproval,
  CreateRefundResult,
  Customer,
  CustomerCreateParams,
  CustomerUpdateParams,
  ListCustomersParams,
  DeletedCustomer,
  PaymentMethod,
  PaymentMethodCard,
  AttachPaymentMethodParams,
  ListPaymentMethodsParams,
  DeletedPaymentMethod,
  Mandate,
  MandateCreateParams,
  ListMandatesParams,
  RevokedMandate,
  Dispute,
  DisputeEvent,
  ListDisputesParams,
  SimulateDisputeParams,
  DisputeWebhookObject,
  WebhookEventType,
  WebhookEvent,
  WebhookEventObject,
  TriggerTestEventParams,
  TestEvent,
  WebhookDeliveryBody,
  WebhookDeliverySignature,
  PingResult,
} from './types';
