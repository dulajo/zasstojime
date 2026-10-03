import type { components } from "./api-schema";

export type Day = components["schemas"]["DayResponse"];
export type Leaderboard = components["schemas"]["LeaderboardResponse"];
export type RecordDay = components["schemas"]["RecordResponse"];
export type Totals = components["schemas"]["TotalsDto"];
export type RouteRank = components["schemas"]["RouteRankDto"];

const baseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080").replace(/\/$/, "");

export class ApiError extends Error {
  constructor(public readonly status: number) {
    super(`API returned HTTP ${status}`);
  }
}

async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`${baseUrl}${path}`, { signal, cache: "no-store" });
  if (!response.ok) throw new ApiError(response.status);
  return (await response.json()) as T;
}

export const api = {
  day: (date: string, signal?: AbortSignal) => get<Day>(`/api/day/${encodeURIComponent(date)}`, signal),
  leaderboard: (date: string, signal?: AbortSignal) =>
    get<Leaderboard>(`/api/day/${encodeURIComponent(date)}/leaderboard`, signal),
  record: (date: string, signal?: AbortSignal) =>
    get<RecordDay>(`/api/day/${encodeURIComponent(date)}/record`, signal),
};
