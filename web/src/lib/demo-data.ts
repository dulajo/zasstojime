import type { Day, Leaderboard, RecordDay, RouteRank, Totals } from "./api";

const totals = (trips: number, departures: number, onTime: number, sumDelay: number): Totals => ({
  trips_total: trips,
  trips_finished: Math.round(trips * 0.84),
  trips_canceled: Math.round(trips * 0.012),
  dep_total: departures,
  dep_on_time: onTime,
  dep_early: Math.round(departures * 0.04),
  dep_late: departures - onTime - Math.round(departures * 0.04),
  dep_late_sum: sumDelay * 2,
  sum_max_delay: sumDelay,
  punctuality_pct: (onTime / departures) * 100,
  average_late_delay_s: 412,
});

const ranks: RouteRank[] = [
  { line: "22", category: "tram", route_id: "demo-22", totals: totals(162, 1934, 1428, 19840), average_max_delay_s: 122 },
  { line: "136", category: "bus_city", route_id: "demo-136", totals: totals(118, 1380, 952, 17410), average_max_delay_s: 148 },
  { line: "S7", category: "train", route_id: "demo-s7", totals: totals(82, 574, 462, 14280), average_max_delay_s: 174 },
  { line: "9", category: "tram", route_id: "demo-9", totals: totals(140, 1652, 1306, 12360), average_max_delay_s: 88 },
];

export function demoData(date: string): { day: Day; leaderboard: Leaderboard; record: RecordDay } {
  const generated = `${date}T15:00:00Z`;
  const common = { date, generated_at: generated, data_as_of: generated, incomplete: false };
  return {
    day: {
      ...common,
      network: totals(4218, 37260, 28142, 574920),
      categories: {
        tram: totals(1540, 14240, 10538, 181420),
        bus_city: totals(1730, 15710, 12110, 206500),
        bus_regional: totals(610, 4510, 3390, 98320),
        train: totals(338, 2800, 2104, 88680),
      },
      running_trips: 341,
      running_late_trips: 74,
    },
    leaderboard: {
      ...common,
      most_total_delay: ranks,
      highest_delay_per_trip: [ranks[2], ranks[1], ranks[0], ranks[3]],
      worst_punctuality: [ranks[1], ranks[0], ranks[2], ranks[3]],
    },
    record: {
      ...common,
      record: { trip_id: "demo-trip", line: "136", headsign: "Letňany", delay_s: 1830,
        at: generated, stop_id: "demo-stop", flagged: false, verified: false },
    },
  };
}
