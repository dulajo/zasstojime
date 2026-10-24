import type { Metadata } from "next";
import { SiteFooter, SiteHeader } from "@/components/SiteHeader";

export const metadata: Metadata = { title: "Metodika — Zpožděno" };

export default function Methodology() {
  return <main>
    <SiteHeader />
    <div className="shell">
      <div className="page-head"><div className="page-title"><span className="section-kicker">Metodika</span>
        <h1>Co se skrývá za <i>čísly?</i></h1></div>
        <a className="page-back" href="/">← Zpět na přehled</a></div>
      <p className="method-lead">Všechna čísla odvozujeme ze zpoždění při odjezdu ze zastávky. Zdroj poloh vozidel je Golemio (CC BY 4.0); projekt není oficiální službou PID ani DPP.</p>

      <div className="method-grid">
        <section><span>01</span><h2>Kdy je odjezd včas?</h2><p>Odjezd v rozmezí 59 sekund před plánem až 179 sekund po plánu. Dřívější odjezd nepovažujeme za dochvilný, protože cestujícímu mohl spoj ujet.</p></section>
        <section><span>02</span><h2>Co znamená index?</h2><p>Podíl změřených odjezdů včas ze všech změřených odjezdů. Zastávky, na kterých zpoždění nezměříme, do jmenovatele nepřidáváme.</p></section>
        <section><span>03</span><h2>Součet zpoždění spojů</h2><p>Za každý spoj vezmeme jeho největší kladné zpoždění při odjezdu a tyto hodnoty sečteme. Nejde o součet času všech cestujících.</p></section>
        <section><span>04</span><h2>Neúplný den</h2><p>Při delším výpadku sběru označíme den jako neúplný. Čísla pak popisují pouze to, co se podařilo změřit.</p></section>
      </div>
      <div className="method-note"><h2>Proč tu není metro?</h2><p>Data metra sbíráme, ale jejich zpoždění zatím nejsou ověřena proti skutečným mimořádnostem. Do veřejného přehledu je zařadíme až po ověření.</p></div>

      <SiteFooter back />
    </div>
  </main>;
}
