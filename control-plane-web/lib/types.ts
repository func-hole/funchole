export type DomainStatus = "PENDING" | "VERIFIED" | "REJECTED";

export type GatewayStatus = "ACTIVE" | "INACTIVE";

export type CertificateProvider = "SELF_SIGNED" | "LETS_ENCRYPT";

export type CertificateStatus = "PENDING" | "ACTIVE" | "FAILED" | "EXPIRED";

export interface AuthTokenResponse {
  accessToken: string;
  tokenType: string;
  issuedAt: string;
  expiresAt: string;
  passwordChangeRequired: boolean;
}

export interface ProfileResponse {
  id: string;
  username: string;
  email: string;
  fullName: string;
}

export interface ProfileRequest {
  fullName?: string;
  password?: string;
}

export interface DomainResponse {
  id: string;
  domainName: string;
  status: DomainStatus;
  verificationCode: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface DomainCreateRequest {
  domainName: string;
}

export interface CertificateSummaryResponse {
  hostname: string;
  wildcardHostname: string;
  provider: CertificateProvider;
  status: CertificateStatus;
  issuedAt: string | null;
  expiresAt: string | null;
}

export interface GatewayResponse {
  id: string;
  appDomainId: string;
  domainName: string;
  name: string;
  uniqueKey: string;
  description: string | null;
  status: GatewayStatus;
  certificate: CertificateSummaryResponse | null;
  createdAt: string;
  updatedAt: string;
}

export interface GatewayCreateRequest {
  name: string;
  description?: string | null;
  appDomainId: string;
  status: GatewayStatus;
}

export type GatewayUpdateRequest = GatewayCreateRequest;

export interface PaginationResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface ApiResponse<T> {
  timestamp: string;
  success: boolean;
  data: T;
}

export interface ApiErrorResponse {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
  details: string[];
}
