import type { components } from "./api-schema";

export type Day = components["schemas"]["DayResponse"];
export type Leaderboard = components["schemas"]["LeaderboardResponse"];
export type RecordDay = components["schemas"]["RecordResponse"];
export type Totals = components["schemas"]["TotalsDto"];
export type RouteRank = components["schemas"]["RouteRankDto"];
export type LineOption = components["schemas"]["LineDto"];
export type StopOption = components["schemas"]["StopDto"];
export type DepartureOption = components["schemas"]["DepartureDto"];
export type MyRoute = components["schemas"]["MyRouteResponse"];
export type RouteDay = components["schemas"]["RouteDayDto"];
export type Trip = components["schemas"]["TripResponse"];
export type TripStop = components["schemas"]["TripStopDto"];
export type CalendarDay = components["schemas"]["CalendarDayResponse"];
export type DayType = "workday" | "saturday" | "sunday_holiday";

export type MyRouteQuery = { line: string; from: number; to: number; time: string; dayType: string };

// Produkční build bez adresy zastaví scripts/check-build-env.mjs; localhost je jen pro `npm run dev`.
const baseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080").replace(/\/$/, "");

export class ApiError extends Error {
  constructor(public readonly status: number) {
    super(`API returned HTTP ${status}`);
  }
}

async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`${baseUrl}${path}`, { signal });
  if (!response.ok) throw new ApiError(response.status);
  return (await response.json()) as T;
}

const segment = encodeURIComponent;

export const api = {
  day: (date: string, signal?: AbortSignal) => get<Day>(`/api/day/${encodeURIComponent(date)}`, signal),
  leaderboard: (date: string, signal?: AbortSignal) =>
    get<Leaderboard>(`/api/day/${encodeURIComponent(date)}/leaderboard`, signal),
  record: (date: string, signal?: AbortSignal) =>
    get<RecordDay>(`/api/day/${encodeURIComponent(date)}/record`, signal),
  lines: (query: string, signal?: AbortSignal) =>
    get<{ items: LineOption[] }>(`/api/lines?q=${segment(query)}`, signal),
  stops: (line: string, signal?: AbortSignal) =>
    get<{ items: StopOption[] }>(`/api/lines/${segment(line)}/stops`, signal),
  departures: (line: string, from: number, dayType: string, headsign: string | null, signal?: AbortSignal) =>
    get<{ items: DepartureOption[] }>(`/api/lines/${segment(line)}/departures?from=${from}&day_type=${segment(dayType)}` +
      (headsign ? `&headsign=${segment(headsign)}` : ""), signal),
  myRoute: ({ line, from, to, time, dayType }: MyRouteQuery, signal?: AbortSignal) =>
    get<MyRoute>(`/api/my-route?line=${segment(line)}&from=${from}&to=${to}&time=${segment(time)}` +
      `&day_type=${segment(dayType)}&period=30d`, signal),
  trip: (date: string, tripId: string, signal?: AbortSignal) =>
    get<Trip>(`/api/trip/${segment(date)}/${segment(tripId)}`, signal),
  calendar: (date: string, signal?: AbortSignal) => get<CalendarDay>(`/api/calendar/${segment(date)}`, signal),
};
