"use client";

import { useQuery } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
import { SiteHeader } from "@/components/SiteHeader";
import { api, ApiError, type LineOption, type MyRoute as MyRouteData, type MyRouteQuery, type RouteDay, type StopOption, type Trip } from "@/lib/api";
import {
  addDays, delayBand, delayBandNames, delayMinutes, formatClock, formatDelay, formatNumber, formatPercent, formatTimestamp,
  formatWeekdayDate, isLiveDay, nowInPrague, todayInPrague,
} from "@/lib/format";

const lineKinds: Record<string, string> = {
  tram: "Tram", bus_city: "Bus", bus_regional: "Bus", train: "Vlak", other: "Linka",
};
const dayTypeNames: Record<string, string> = {
  workday: "pracovní dny", saturday: "soboty", sunday_holiday: "neděle a svátky",
};
const noDirection = "__none__";

type Selection = { line: string; dir: string; from: number | null; to: number | null; date: string; time: string };

const emptySelection: Selection = { line: "", dir: "", from: null, to: null, date: "", time: "" };
const toNumber = (value: string | null) => value && /^\d+$/.test(value) ? Number(value) : null;
const directionKey = (stop: StopOption) => stop.headsign ?? noDirection;
const notFound = (error: unknown) => error instanceof ApiError && error.status === 404;

function DelayChip({ seconds, estimated = false }: { seconds: number | null | undefined; estimated?: boolean }) {
  if (seconds == null) return <span className="delay-chip delay-none">nezměřeno</span>;
  const band = delayBand(seconds);
  return <span className={`delay-chip delay-${band}`} title={delayBandNames[band]}>
    {formatDelay(seconds)}{estimated && <small> odhad</small>}
  </span>;
}

/** Jak dopadl nástup nebo cíl, celou větou. */
function describe(seconds: number): string {
  const band = delayBand(seconds);
  const minutes = Math.abs(delayMinutes(seconds));
  if (band === "early") return `o ${minutes} min dřív, než měl`;
  if (band === "ontime") return "včas";
  return `se zpožděním ${minutes} min`;
}

function verdict(day: RouteDay): string {
  const boarding = day.boarding_delay_s!;
  const arrival = day.arrival_delay_s!;
  if (delayBand(boarding) === "ontime" && delayBand(arrival) === "ontime") return "Spoj jel včas na nástupu i v cíli.";
  return `Na nástup přijel ${describe(boarding)}, do cíle dorazil ${describe(arrival)}.`;
}

function rideChange(seconds: number): string {
  const minutes = delayMinutes(seconds);
  if (minutes >= 1) return `Cestou nabral ${minutes} min.`;
  if (minutes <= -1) return `Cestou dohnal ${Math.abs(minutes)} min.`;
  return "Cestou se zpoždění nezměnilo.";
}

function Field({ label, htmlFor, area, children, hint }: {
  label: string; htmlFor: string; area: string; children: React.ReactNode; hint?: string;
}) {
  return <div className="route-field" style={{ gridArea: area }}><label htmlFor={htmlFor}>{label}</label>{children}
    {hint && <small>{hint}</small>}</div>;
}

function TripProfile({ trip, fromNode, toNode }: { trip: Trip; fromNode: number; toNode: number }) {
  const fromIndex = trip.stops.findIndex((stop) => stop.node_id === fromNode);
  const toIndex = trip.stops.findIndex((stop, index) => index > fromIndex && stop.node_id === toNode);
  const inSegment = (index: number) => fromIndex >= 0 && index >= fromIndex && (toIndex < 0 || index <= toIndex);
  const state = trip.status === "running" ? "Spoj právě jede" : trip.status === "canceled" ? "Spoj byl zrušen" : "Spoj dojel";
  return <section className="section-block" aria-labelledby="profile-title">
    <div className="section-heading"><div><span className="section-kicker">Zastávku po zastávce</span>
      <h2 id="profile-title">Průběh spoje</h2></div>
      <p>Zpoždění při odjezdu ze zastávky, kde chybí, tak při příjezdu. Zvýrazněný je váš úsek. Zastávky projeté mezi dvěma měřeními chybí.</p></div>
    <div className="trip-panel">
      <div className="trip-status">{trip.status === "running" && <span className="live-dot" />}
        <strong>{state}</strong>
        {trip.data_as_of && <span>Data k {formatTimestamp(trip.data_as_of)}</span>}</div>
      {trip.stops.length === 0 ? <p className="muted">Zatím nemáme žádnou změřenou zastávku.</p> :
        <ol className="trip-stops">{trip.stops.map((stop, index) => {
          const planned = stop.planned_departure ?? stop.planned_arrival;
          const delay = stop.departure_delay_s ?? stop.arrival_delay_s;
          const mark = index === fromIndex ? "Nástup" : index === toIndex ? "Výstup" : null;
          return <li key={stop.sequence} className={inSegment(index) ? "in-segment" : undefined}>
            <span className="trip-time">{planned ? formatClock(planned) : "—"}</span>
            <span className="trip-stop">{stop.name ?? stop.stop_id}{mark && <em>{mark}</em>}</span>
            <DelayChip seconds={delay} estimated={stop.departure_delay_s == null && stop.arrival_delay_s != null} />
          </li>;
        })}</ol>}
    </div>
  </section>;
}

function History({ days, selected, dayType, summary, incomplete }: {
  days: RouteDay[]; selected: string; dayType: string; summary: MyRouteData["summary"]; incomplete: boolean;
}) {
  const others = days.filter((day) => day.date !== selected).sort((a, b) => b.date.localeCompare(a.date));
  return <section className="section-block" aria-labelledby="history-title">
    <div className="section-heading"><div><span className="section-kicker">Za posledních 30 dní</span>
      <h2 id="history-title">Jak tenhle spoj jezdí</h2></div>
      <p>Stejný spoj ({dayTypeNames[dayType] ?? dayType}) hledáme podle linky, zastávky a plánovaného času, ne podle čísla spoje.</p></div>
    {incomplete && <div className="notice history-notice">Ne všechny dny za posledních 30 dní máme celé: sběr běží od nedávna
      nebo měl výpadky. Souhrn popisuje jen změřené dny.</div>}
    {summary.measured_days > 0 && <div className="metric-grid metric-grid-3">
      <div className="metric-card"><p className="eyebrow">Včas na nástupu</p><strong>{formatPercent(summary.on_time_boarding_pct)}</strong>
        <span>{formatNumber(summary.measured_days)} změřených dní</span></div>
      <div className="metric-card"><p className="eyebrow">Průměrně v cíli</p><strong>{formatDelay(summary.average_arrival_delay_s)}</strong>
        <span>Nejhůř {formatDelay(summary.max_arrival_delay_s)}</span></div>
      <div className="metric-card"><p className="eyebrow">Průměrně cestou</p><strong>{formatDelay(summary.average_extra_ride_s)}</strong>
        <span>Kolik spoj mezi vašimi zastávkami nabere</span></div>
    </div>}
    {others.length === 0 ? <div className="empty-panel">Jiné dny zatím nemáme. Další přibudou, jak poběží sběr.</div> :
      <div className="history-panel"><table className="history-table">
        <thead><tr><th scope="col">Den</th><th scope="col">Nástup</th><th scope="col">Cíl</th></tr></thead>
        <tbody>{others.map((day) => <tr key={day.date}>
          <td>{formatWeekdayDate(day.date)}</td>
          <td><DelayChip seconds={day.boarding_delay_s} estimated={day.boarding_estimated} /></td>
          <td>{day.running ? <span className="delay-chip delay-none">jede</span> : <DelayChip seconds={day.arrival_delay_s} />}</td>
        </tr>)}</tbody>
      </table></div>}
  </section>;
}

function useRoute(query: MyRouteQuery | null, live: boolean) {
  return useQuery({
    queryKey: ["my-route", query], queryFn: ({ signal }) => api.myRoute(query!, signal),
    enabled: query != null, refetchInterval: live ? 60_000 : false,
  });
}

export function MyRoute() {
  const [ready, setReady] = useState(false);
  const [today, setToday] = useState("");
  const [selection, setSelection] = useState<Selection>(emptySelection);
  const [lineInput, setLineInput] = useState("");

  // Výběr se drží v adrese, aby šel uložit do záložek a poslat dál.
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const now = todayInPrague();
    const line = params.get("line")?.trim() ?? "";
    const date = params.get("date");
    setToday(now);
    setLineInput(line);
    setSelection({
      line, dir: params.get("dir") ?? "", from: toNumber(params.get("from")), to: toNumber(params.get("to")),
      date: date && /^\d{4}-\d{2}-\d{2}$/.test(date) && date <= now && date >= addDays(now, -29) ? date : now,
      time: /^\d{2}:\d{2}$/.test(params.get("time") ?? "") ? params.get("time")! : "",
    });
    setReady(true);
  }, []);
  useEffect(() => {
    if (!ready) return;
    const params = new URLSearchParams();
    if (selection.line) params.set("line", selection.line);
    if (selection.dir) params.set("dir", selection.dir);
    if (selection.from != null) params.set("from", String(selection.from));
    if (selection.to != null) params.set("to", String(selection.to));
    if (selection.time) params.set("time", selection.time);
    if (selection.date && selection.date !== today) params.set("date", selection.date);
    const search = params.toString();
    window.history.replaceState(null, "", search ? `?${search}` : window.location.pathname);
  }, [ready, selection, today]);

  const update = (patch: Partial<Selection>) => setSelection((current) => ({ ...current, ...patch }));
  const query = lineInput.trim();
  const linesQuery = useQuery({ queryKey: ["lines", query], queryFn: ({ signal }) => api.lines(query, signal),
    enabled: ready && query.length > 0 && query !== selection.line, staleTime: 300_000 });
  const lineOptions = useMemo(() => {
    const seen = new Set<string>();
    return (linesQuery.data?.items ?? []).filter((item: LineOption) =>
      item.category !== "metro" && !seen.has(item.line) && seen.add(item.line) !== null).slice(0, 12);
  }, [linesQuery.data]);
  const chooseLine = (line: string) => {
    setLineInput(line);
    update({ line, dir: "", from: null, to: null, time: "" });
  };

  const stopsQuery = useQuery({ queryKey: ["stops", selection.line], queryFn: ({ signal }) => api.stops(selection.line, signal),
    enabled: Boolean(selection.line), staleTime: 300_000 });
  const stops = useMemo(() => stopsQuery.data?.items ?? [], [stopsQuery.data]);
  const directions = useMemo(() => [...new Set(stops.map(directionKey))], [stops]);
  useEffect(() => {
    if (directions.length === 1 && selection.dir !== directions[0]) update({ dir: directions[0], from: null, to: null, time: "" });
  }, [directions, selection.dir]);
  const directionStops = useMemo(() => stops.filter((stop) => directionKey(stop) === selection.dir)
    .sort((a, b) => a.sequence - b.sequence), [stops, selection.dir]);
  const fromStop = directionStops.find((stop) => stop.node_id === selection.from);
  const toOptions = fromStop ? directionStops.filter((stop) => stop.sequence > fromStop.sequence) : [];
  const toStop = toOptions.find((stop) => stop.node_id === selection.to);

  const calendarQuery = useQuery({ queryKey: ["calendar", selection.date], queryFn: ({ signal }) => api.calendar(selection.date, signal),
    enabled: Boolean(selection.date), staleTime: Infinity });
  const dayType = calendarQuery.data?.day_type;
  const departuresQuery = useQuery({
    queryKey: ["departures", selection.line, selection.from, dayType, selection.dir],
    queryFn: ({ signal }) => api.departures(selection.line, selection.from!, dayType!,
      selection.dir === noDirection ? null : selection.dir, signal),
    enabled: Boolean(selection.line && fromStop && dayType), staleTime: 300_000,
  });
  const times = useMemo(() => (departuresQuery.data?.items ?? []).map((item) => item.time), [departuresQuery.data]);
  // Bez zvoleného času vezmeme poslední spoj, který už dnes odjel; u starších dní první v seznamu.
  useEffect(() => {
    if (times.length === 0 || times.includes(selection.time)) return;
    const now = nowInPrague();
    const past = selection.date === today ? times.filter((time) => time <= now) : [];
    update({ time: past[past.length - 1] ?? times[0] });
  }, [times, selection.time, selection.date, today]);

  const live = Boolean(selection.date) && isLiveDay(selection.date);
  const routeQuery = useRoute(selection.line && fromStop && toStop && selection.time && dayType ? {
    line: selection.line, from: fromStop.node_id, to: toStop.node_id, time: selection.time, dayType,
    headsign: selection.dir === noDirection ? null : selection.dir,
  } : null, live);
  const day = routeQuery.data?.days.find((item) => item.date === selection.date);
  const tripQuery = useQuery({
    queryKey: ["trip", selection.date, day?.trip_id], queryFn: ({ signal }) => api.trip(selection.date, day!.trip_id, signal),
    enabled: Boolean(day), refetchInterval: (state) => state.state.data?.status === "running" ? 30_000 : false,
    retry: (failures, error) => !notFound(error) && failures < 2,
  });
  const trip = tripQuery.data;

  const routeLabel = fromStop && toStop ? `${fromStop.name} → ${toStop.name}` : "";
  const notYet = selection.date === today && selection.time > nowInPrague();

  return <main>
    <SiteHeader active="my-route" />
    <div className="shell">
      <div className="page-head"><div className="page-title"><span className="section-kicker">Moje trasa</span>
        <h1>Jak dnes jel <i>můj spoj?</i></h1></div>
        <p className="page-lead">Vyberte linku, zastávky a čas odjezdu. Ukážeme, jak spoj jel a kde nabral zpoždění.</p></div>

      <section className="route-form" aria-label="Výběr spoje">
        <Field label="Linka" htmlFor="route-line" area="line">
          <input id="route-line" inputMode="search" autoComplete="off" placeholder="např. 17, 136, S7" value={lineInput}
            onChange={(event) => setLineInput(event.target.value.toUpperCase())}
            onKeyDown={(event) => {
              if (event.key !== "Enter") return;
              const exact = lineOptions.find((item) => item.line === query);
              if (exact) chooseLine(exact.line);
            }} />
          {query && query !== selection.line && <div className="line-options" role="listbox" aria-label="Nalezené linky">
            {linesQuery.isPending ? <span className="muted">Hledáme…</span> :
              lineOptions.length === 0 ? <span className="muted">Linku jsme nenašli.</span> :
                lineOptions.map((item) => <button key={item.line} type="button" role="option" aria-selected={false}
                  onClick={() => chooseLine(item.line)}><small>{lineKinds[item.category] ?? "Linka"}</small>{item.line}</button>)}
          </div>}
        </Field>
        <Field label="Směr" htmlFor="route-dir" area="dir">
          <select id="route-dir" value={selection.dir} disabled={directions.length === 0}
            onChange={(event) => update({ dir: event.target.value, from: null, to: null, time: "" })}>
            <option value="" disabled>{selection.line ? stopsQuery.isPending ? "Načítáme…" : "Vyberte směr" : "Nejdřív linku"}</option>
            {directions.map((dir) => <option key={dir} value={dir}>{dir === noDirection ? "Bez uvedeného směru" : `Směr ${dir}`}</option>)}
          </select>
        </Field>
        <Field label="Nastupuji" htmlFor="route-from" area="from">
          <select id="route-from" value={fromStop ? String(fromStop.node_id) : ""} disabled={directionStops.length === 0}
            onChange={(event) => update({ from: Number(event.target.value), to: null, time: "" })}>
            <option value="" disabled>Vyberte zastávku</option>
            {directionStops.slice(0, -1).map((stop) => <option key={stop.node_id} value={stop.node_id}>{stop.name}</option>)}
          </select>
        </Field>
        <Field label="Vystupuji" htmlFor="route-to" area="to">
          <select id="route-to" value={toStop ? String(toStop.node_id) : ""} disabled={toOptions.length === 0}
            onChange={(event) => update({ to: Number(event.target.value) })}>
            <option value="" disabled>Vyberte zastávku</option>
            {toOptions.map((stop) => <option key={stop.node_id} value={stop.node_id}>{stop.name}</option>)}
          </select>
        </Field>
        <Field label="Den" htmlFor="route-date" area="date" hint={dayType ? dayTypeNames[dayType] : undefined}>
          <input id="route-date" type="date" value={selection.date} min={today ? addDays(today, -29) : undefined} max={today}
            onChange={(event) => event.target.value && update({ date: event.target.value, time: "" })} />
        </Field>
        <Field label="Odjezd" htmlFor="route-time" area="time">
          <select id="route-time" value={times.includes(selection.time) ? selection.time : ""} disabled={times.length === 0}
            onChange={(event) => update({ time: event.target.value })}>
            <option value="" disabled>{fromStop ? departuresQuery.isPending ? "Načítáme…" : "Žádné odjezdy" : "Nejdřív zastávku"}</option>
            {times.map((time) => <option key={time} value={time}>{time}</option>)}
          </select>
        </Field>
      </section>
      {selection.line && stopsQuery.data && stops.length === 0 && <div className="notice">
        Tuhle linku jsme zatím nezměřili. Zastávky nabízíme podle toho, kde spoje linky opravdu projely.</div>}

      {!routeQuery.data ? routeQuery.isError ? <div className="state-panel"><h2>Spoj se nepodařilo načíst</h2>
        <p>Zkuste to za chvíli znovu.</p></div> :
        <div className="empty-panel route-hint">{routeQuery.isFetching ? "Hledáme spoj…" :
          "Po výběru linky, obou zastávek a času tu uvidíte, jak spoj jel. Zastávky a časy nabízíme podle toho, co jsme za poslední dva týdny opravdu změřili."}</div> :
        <>
          <section className="headline route-headline" aria-labelledby="route-result-title">
            <div className="headline-live">
              <span className="headline-kicker">{trip?.status === "running" && <span className="live-dot" />}
                Linka {selection.line} · {selection.time} · {formatWeekdayDate(selection.date)}</span>
              <h2 id="route-result-title" className="route-title">{routeLabel}</h2>
              {day?.measured ? <>
                <p className="headline-sentence">{verdict(day)} {rideChange(day.arrival_delay_s! - day.boarding_delay_s!)}</p>
                {day.boarding_estimated && <span className="headline-detail">Odjezd z nástupní zastávky jsme nezachytili, nástup odhadujeme podle příjezdu.</span>}
              </> : day ? <p className="headline-sentence">{day.running ?
                `Spoj právě jede. Na nástup přijel ${day.boarding_delay_s != null ? describe(day.boarding_delay_s) : "bez změřeného zpoždění"}, cíl ještě nemáme.` :
                day.boarding_delay_s != null ? `Na nástup přijel ${describe(day.boarding_delay_s)}. Do cílové zastávky jsme ho nezachytili, projel ji nejspíš mezi dvěma měřeními.` :
                  "Spoj jsme v tento den změřili jen zčásti, nástup chybí."}</p> :
                <p className="headline-sentence">{notYet ? `Spoj v ${selection.time} dnes teprve pojede. Výsledek se objeví, jakmile vyjede.` :
                  "V tento den jsme spoj nezměřili. Mohl nejet, nebo jsme ho nezachytili."}</p>}
            </div>
            <div className="route-numbers">
              <div><span className="route-label">Nástup</span><DelayChip seconds={day?.boarding_delay_s} estimated={day?.boarding_estimated} /></div>
              <div><span className="route-label">Cíl</span>{day?.running ? <span className="delay-chip delay-none">ještě jede</span> :
                <DelayChip seconds={day?.arrival_delay_s} />}</div>
              <div><span className="route-label">Cestou</span><DelayChip seconds={day?.measured ? day.arrival_delay_s! - day.boarding_delay_s! : null} /></div>
            </div>
          </section>
          {trip && fromStop && toStop && <TripProfile trip={trip} fromNode={fromStop.node_id} toNode={toStop.node_id} />}
          <History days={routeQuery.data.days} selected={selection.date} dayType={routeQuery.data.day_type}
            summary={routeQuery.data.summary} incomplete={routeQuery.data.incomplete} />
        </>}

      <footer className="site-footer"><div><strong>Zpožděno.</strong><p>Neoficiální pohled na dochvilnost PID. Nejsme součástí PID ani DPP.</p></div>
        <div><p>Zdroj dat: Golemio, licence CC BY 4.0.</p><a href="/metodika/">Jak čísla počítáme →</a></div></footer>
    </div>
  </main>;
}
