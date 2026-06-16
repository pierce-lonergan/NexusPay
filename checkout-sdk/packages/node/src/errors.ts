/**
 * Typed error classes for @nexuspay/node.
 *
 * NexusPayError mirrors the INT-2 error envelope shape
 *   { error: { type, code, message, request_id } }
 * (common/domain ApiError). It never embeds the apiKey or webhook secret.
 */

export type NexusPayErrorType =
  | 'validation_error'
  | 'not_found'
  | 'unauthorized'
  | 'forbidden'
  | 'conflict'
  | 'payment_error'
  | 'rate_limit_error'
  | 'internal_error'
  | 'session_error'
  | 'network_error'; // local timeout/transport (not server-sourced)

export interface ApiErrorBody {
  type: string;
  code: string;
  message: string;
  request_id?: string;
}

interface NexusPayErrorArgs {
  type: string;
  code: string;
  message: string;
  requestId?: string;
  status?: number;
}

export class NexusPayError extends Error {
  readonly type: NexusPayErrorType | string;
  readonly code: string;
  readonly requestId?: string;
  /** HTTP status; undefined for local network errors. */
  readonly status?: number;

  constructor(args: NexusPayErrorArgs) {
    super(args.message);
    this.name = 'NexusPayError';
    this.type = args.type;
    this.code = args.code;
    this.requestId = args.requestId;
    this.status = args.status;
    // Restore prototype chain for instanceof under transpiled targets.
    Object.setPrototypeOf(this, NexusPayError.prototype);
  }

  /**
   * Maps an INT-2 `{ error: { type, code, message, request_id } }` envelope
   * (already-parsed body) plus the HTTP status into a NexusPayError.
   * Falls back to a generic shape when the body is not the envelope shape
   * (mirrors @nexuspay/js http-client behaviour).
   */
  static fromEnvelope(body: unknown, status: number): NexusPayError {
    const error = extractApiError(body);
    if (error) {
      return new NexusPayError({
        type: error.type,
        code: error.code,
        message: error.message,
        requestId: error.request_id,
        status,
      });
    }
    return new NexusPayError({
      type: status === 429 ? 'rate_limit_error' : 'api_error',
      code: `http_${status}`,
      message: `HTTP ${status}`,
      status,
    });
  }
}

function extractApiError(body: unknown): ApiErrorBody | undefined {
  if (typeof body !== 'object' || body === null) return undefined;
  const error = (body as { error?: unknown }).error;
  if (typeof error !== 'object' || error === null) return undefined;
  const e = error as Record<string, unknown>;
  if (typeof e.type !== 'string' || typeof e.message !== 'string') return undefined;
  return {
    type: e.type,
    code: typeof e.code === 'string' ? e.code : 'unknown',
    message: e.message,
    request_id: typeof e.request_id === 'string' ? e.request_id : undefined,
  };
}

export type SignatureVerificationCode =
  | 'missing_signature'
  | 'invalid_signature'
  | 'invalid_payload'
  | 'timestamp_out_of_tolerance';

export class SignatureVerificationError extends Error {
  readonly code: SignatureVerificationCode;

  constructor(message: string, code: SignatureVerificationCode) {
    super(message);
    this.name = 'SignatureVerificationError';
    this.code = code;
    Object.setPrototypeOf(this, SignatureVerificationError.prototype);
  }
}
