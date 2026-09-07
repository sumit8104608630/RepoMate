"use client";

import { RequireAuth } from "@/components/providers/require-auth";
import { AppShell } from "@/components/layout/app-shell";
import { OverviewDashboard } from "@/components/dashboard/overview-dasboard";

export default function DashboardOverviewPage() {
  return (
    <RequireAuth>
      <AppShell
        title="Overview"
        description="Your workspace at a glance."
        hideHeader
      >
        <OverviewDashboard />
      </AppShell>
    </RequireAuth>
  );
}
