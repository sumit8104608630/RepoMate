"use client";

import { RequireAuth } from "@/components/providers/require-auth";
import { AppShell } from "@/components/layout/app-shell";
import { SettingDashboard } from "@/components/dashboard/setting-dashboard";

export default function DashboardSettingsPage() {
  return (
    <RequireAuth>
      <AppShell
        title="Settings"
        description="Manage your DevPilot account."
        hideHeader
      >
        <SettingDashboard />
      </AppShell>
    </RequireAuth>
  );
}
