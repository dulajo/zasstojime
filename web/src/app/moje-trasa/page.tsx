import type { Metadata } from "next";
import { MyRoute } from "@/components/MyRoute";

export const metadata: Metadata = {
  title: "Moje trasa — Zpožděno",
  description: "Jak jel váš spoj: zpoždění na nástupu, v cíli a zastávku po zastávce.",
};

export default function MyRoutePage() {
  return <MyRoute />;
}
