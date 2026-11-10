    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String key = preference.getKey();
        if (key == null) return false;

        switch (key) {
            case "hud_enabled":   // must match the key in XML
                boolean enable = (Boolean) newValue;
                if (enable) {
                    startHudService();
                } else {
                    stopHudService();
                }
                // Return true to accept the change (the switch will update)
                return true;
        }
        return false;
    }

    private void startHudService() {
        activity.startHudService();
        Toast.makeText(activity, "HUD started", Toast.LENGTH_SHORT).show();
        activity.drawerLayout.closeDrawer(GravityCompat.START);
    }

    private void stopHudService() {
        activity.stopHudService();    // <-- use activity's method
        Toast.makeText(activity, "HUD stopped", Toast.LENGTH_SHORT).show();
        activity.drawerLayout.closeDrawer(GravityCompat.START);
    }
}
