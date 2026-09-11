export type DomainStatus = "PENDING" | "VERIFIED" | "REJECTED";

export type GatewayStatus = "ACTIVE" | "INACTIVE";

export type CertificateProvider = "SELF_SIGNED" | "LETS_ENCRYPT";

export type CertificateStatus = "PENDING" | "ACTIVE" | "FAILED" | "EXPIRED";

export type FlowVersionStatus = "DRAFT" | "ADOPTED" | "ARCHIVED";

export type FlowStepComponentType = "FUNCTION" | "RESPONSE";

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

export interface FlowResponse {
  id: string;
  gatewayId: string;
  gatewayName: string;
  activeFlowVersionId: string | null;
  activeFlowVersionStatus: FlowVersionStatus | null;
  flowKey: string;
  name: string;
  description: string | null;
  httpMethod: string;
  path: string;
  priority: number;
  createdAt: string;
  updatedAt: string;
}

export interface FlowCreateRequest {
  flowKey: string;
  name: string;
  description?: string | null;
  gatewayId: string;
  httpMethod: string;
  path: string;
  priority?: number | null;
}

export interface FlowUpdateRequest {
  name: string;
  description?: string | null;
  gatewayId: string;
  httpMethod: string;
  path: string;
  priority?: number | null;
}

export interface FlowStepResponse {
  id: string;
  flowVersionId: string;
  stepKey: string;
  componentType: FlowStepComponentType;
  position: number;
  componentId: string;
  componentVersionId: string;
  metadata: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface FlowStepCreateRequest {
  stepKey: string;
  componentType: FlowStepComponentType;
  position: number;
  componentId: string;
  componentVersionId: string;
  metadata?: string | null;
}

export type FlowStepUpdateRequest = FlowStepCreateRequest;

export interface FlowVersionResponse {
  id: string;
  flowId: string;
  version: number;
  status: FlowVersionStatus;
  runtime: string;
  metadata: string | null;
  steps: FlowStepResponse[] | null;
  createdAt: string;
  updatedAt: string;
  adoptedAt: string | null;
  archivedAt: string | null;
}

export interface FlowVersionCreateRequest {
  runtime?: string | null;
  metadata?: string | null;
}

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
