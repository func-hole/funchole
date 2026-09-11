import { clearToken, getToken } from "@/lib/auth";
import type {
  ApiErrorResponse,
  ApiResponse,
  AuthTokenResponse,
  DomainCreateRequest,
  DomainResponse,
  FlowCreateRequest,
  FlowResponse,
  FlowStepCreateRequest,
  FlowStepResponse,
  FlowStepUpdateRequest,
  FlowUpdateRequest,
  FlowVersionCreateRequest,
  FlowVersionResponse,
  GatewayCreateRequest,
  GatewayResponse,
  GatewayUpdateRequest,
  PaginationResponse,
  ProfileRequest,
  ProfileResponse,
} from "@/lib/types";

const API_BASE_URL =
  process.env.NEXT_PUBLIC_CONTROLPLANE_URL ?? "http://localhost:7080";

export class ApiError extends Error {
  readonly status: number;
  readonly details: string[];

  constructor(status: number, message: string, details: string[] = []) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.details = details;
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = getToken();
  const headers = new Headers(init.headers);
  if (init.body !== undefined) {
    headers.set("Content-Type", "application/json");
  }
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, { ...init, headers });
  } catch {
    throw new ApiError(0, "Cannot reach the controlplane API");
  }

  if (response.status === 401) {
    clearToken();
    if (typeof window !== "undefined" && !window.location.pathname.startsWith("/login")) {
      window.location.replace("/login");
    }
    throw new ApiError(401, "Session expired");
  }

  if (!response.ok) {
    const body = (await response.json().catch(() => null)) as ApiErrorResponse | null;
    throw new ApiError(
      response.status,
      body?.message ?? `Request failed with status ${response.status}`,
      body?.details ?? []
    );
  }

  const body = (await response.json()) as ApiResponse<T>;
  return body.data;
}

export const api = {
  login(username: string, password: string): Promise<AuthTokenResponse> {
    return request("/api/v1/auth/token", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    });
  },

  getProfile(): Promise<ProfileResponse> {
    return request("/api/v1/profile/me");
  },

  updateProfile(payload: ProfileRequest): Promise<ProfileResponse> {
    return request("/api/v1/profile/me", {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },

  listDomains(page: number, size: number): Promise<PaginationResponse<DomainResponse>> {
    return request(`/api/v1/domains?page=${page}&size=${size}`);
  },

  createDomain(payload: DomainCreateRequest): Promise<DomainResponse> {
    return request("/api/v1/domains", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  initiateDomainVerification(id: string): Promise<DomainResponse> {
    return request(`/api/v1/domains/${id}/verification`, { method: "POST" });
  },

  listGateways(page: number, size: number): Promise<PaginationResponse<GatewayResponse>> {
    return request(`/api/v1/gateways?page=${page}&size=${size}`);
  },

  createGateway(payload: GatewayCreateRequest): Promise<GatewayResponse> {
    return request("/api/v1/gateways", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  updateGateway(id: string, payload: GatewayUpdateRequest): Promise<GatewayResponse> {
    return request(`/api/v1/gateways/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },

  deleteGateway(id: string): Promise<Record<string, string>> {
    return request(`/api/v1/gateways/${id}`, { method: "DELETE" });
  },

  listFlows(page: number, size: number): Promise<PaginationResponse<FlowResponse>> {
    return request(`/api/v1/flows?page=${page}&size=${size}`);
  },

  getFlow(id: string): Promise<FlowResponse> {
    return request(`/api/v1/flows/${id}`);
  },

  createFlow(payload: FlowCreateRequest): Promise<FlowResponse> {
    return request("/api/v1/flows", { method: "POST", body: JSON.stringify(payload) });
  },

  updateFlow(id: string, payload: FlowUpdateRequest): Promise<FlowResponse> {
    return request(`/api/v1/flows/${id}`, { method: "PUT", body: JSON.stringify(payload) });
  },

  deleteFlow(id: string): Promise<Record<string, string>> {
    return request(`/api/v1/flows/${id}`, { method: "DELETE" });
  },

  listFlowVersions(
    flowId: string,
    page: number,
    size: number
  ): Promise<PaginationResponse<FlowVersionResponse>> {
    return request(`/api/v1/flows/${flowId}/versions?page=${page}&size=${size}`);
  },

  getFlowVersion(flowId: string, versionId: string): Promise<FlowVersionResponse> {
    return request(`/api/v1/flows/${flowId}/versions/${versionId}`);
  },

  createFlowVersion(
    flowId: string,
    payload: FlowVersionCreateRequest
  ): Promise<FlowVersionResponse> {
    return request(`/api/v1/flows/${flowId}/versions`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  adoptFlowVersion(flowId: string, versionId: string): Promise<FlowVersionResponse> {
    return request(`/api/v1/flows/${flowId}/versions/${versionId}/adopt`, { method: "POST" });
  },

  archiveFlowVersion(flowId: string, versionId: string): Promise<FlowVersionResponse> {
    return request(`/api/v1/flows/${flowId}/versions/${versionId}/archive`, { method: "POST" });
  },

  deleteFlowVersion(flowId: string, versionId: string): Promise<Record<string, string>> {
    return request(`/api/v1/flows/${flowId}/versions/${versionId}`, { method: "DELETE" });
  },

  listFlowSteps(flowId: string, versionId: string): Promise<FlowStepResponse[]> {
    return request(`/api/v1/flows/${flowId}/versions/${versionId}/steps`);
  },

  createFlowStep(
    flowId: string,
    versionId: string,
    payload: FlowStepCreateRequest
  ): Promise<FlowStepResponse> {
    return request(`/api/v1/flows/${flowId}/versions/${versionId}/steps`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  updateFlowStep(
    flowId: string,
    versionId: string,
    stepId: string,
    payload: FlowStepUpdateRequest
  ): Promise<FlowStepResponse> {
    return request(`/api/v1/flows/${flowId}/versions/${versionId}/steps/${stepId}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },

  deleteFlowStep(flowId: string, versionId: string, stepId: string): Promise<Record<string, string>> {
    return request(`/api/v1/flows/${flowId}/versions/${versionId}/steps/${stepId}`, {
      method: "DELETE",
    });
  },
};
