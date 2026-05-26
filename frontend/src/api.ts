// Thin wrapper around the Java backend. Vite proxies /api → :8090.
const BASE = '/api';

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`);
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} on ${path}`);
  return res.json() as Promise<T>;
}

async function put<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} on ${path}`);
  return res.json() as Promise<T>;
}

export type Snapshot = Record<string, number>;

export interface PeriodBucket {
  period: string;
  start: string;
  end: string;
  submitted: number;
  interviewed: number;
  yetToBeInterviewed: number;
  inProgress: number;
  top10Hits: number;
}

export interface Application {
  rowId: number;
  applicationDate: string | null;
  company: string | null;
  jobTitle: string | null;
  location: string | null;
  workMode: string | null;
  postingUrl: string | null;
  applicantCountAtSubmit: number | null;
  applicationPath: string | null;
  resumeUsed: string | null;
  resumeFolder: string | null;
  confirmationId: string | null;
  status: string | null;
  lastStatusChange: string | null;
  recruiterContact: string | null;
  notes: string | null;
}

export interface PendingJd {
  id: string;
  capturedAt: string;
  title: string | null;
  company: string | null;
  location: string | null;
  workMode: string | null;
  postingUrl: string | null;
  applicantCount: number | null;
  postedMinutesAgo?: number | null;
  reposted?: boolean;
  useBaseResume?: boolean;
  jd: string | null;
  processed: boolean;
  processedAt?: string;
  skipped?: boolean;
  skipReason?: string;
  trackerRowId?: number;
  archivePath?: string;
  matchScore?: number;
  note?: string;
  submitted?: boolean;
  submittedAt?: string;
  confirmationId?: string;
  applicationPath?: string;
}

async function post<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} on ${path}`);
  return res.json() as Promise<T>;
}

export const api = {
  health: () => get<Record<string, unknown>>('/health'),
  getConfig: () => get<Record<string, unknown>>('/config'),
  putConfig: (c: unknown) => put<Record<string, unknown>>('/config', c),
  getWorkflow: () => get<Record<string, unknown>>('/workflow'),
  putWorkflow: (w: unknown) => put<Record<string, unknown>>('/workflow', w),
  getState: () => get<Record<string, unknown>>('/state'),
  applications: () => get<Application[]>('/applications'),
  snapshot: () => get<Snapshot>('/reporting/snapshot'),
  daily: (days = 30) => get<PeriodBucket[]>(`/reporting/daily?days=${days}`),
  weekly: (weeks = 12) => get<PeriodBucket[]>(`/reporting/weekly?weeks=${weeks}`),
  monthly: (months = 12) => get<PeriodBucket[]>(`/reporting/monthly?months=${months}`),
  pendingList: () => get<PendingJd[]>('/pending'),
  pendingAdd: (jd: Partial<PendingJd>) => post<PendingJd>('/pending', jd),
  pendingProcess: (id: string) => post<Record<string, unknown>>(`/pending/${id}/process`, {}),
  pendingReprocess: (id: string, opts?: { useBaseResume?: boolean }) =>
    post<Record<string, unknown>>(`/pending/${id}/reprocess`, opts ?? {}),
  pendingProcessAll: () => post<Record<string, unknown>[]>('/pending/process-all', {}),
  pendingMarkSubmitted: (id: string, body: { confirmationId?: string; applicationPath?: string; note?: string }) =>
    post<Record<string, unknown>>(`/pending/${id}/mark-submitted`, body),
};
