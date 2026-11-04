# Airvox Admin Portal

The back office (business management) console for the Airvox team. Riders use the
Airvox app; this web portal is for staff.

Screens so far: staff sign-in, Dashboard and Users. Sign-in, the Dashboard and
the Users list use Supabase. The Dashboard shows how many riders are logged in.

## Supabase setup (once)

1. Create `.env.local` in this folder (it is git-ignored):

   ```
   VITE_SUPABASE_URL=https://your-project.supabase.co
   VITE_SUPABASE_ANON_KEY=your-publishable-key
   ```

   Never put the `service_role` / secret key here.
2. In Supabase, Authentication > Users > Add user: create your staff login.
3. In Supabase, SQL Editor: run `supabase/setup.sql`, with your email in the last part.
   Only accounts listed in the `staff` table can sign in to the portal.
4. Run `supabase/dashboard.sql`: the dashboard tables and the `dashboard_summary()` API.
5. Optional: run `supabase/sample-data.sql` to see the dashboard with sample riders.
   The bottom of that file removes the sample rows again.

## Run it on another computer

1. Install Node.js 18 or newer from https://nodejs.org.
2. Copy this whole folder to the computer, including `.env.local` (you don't need `node_modules`).
3. In a terminal, inside this folder:

   ```bash
   npm install
   npm run dev
   ```

4. Open http://localhost:5180 in a browser.

## Build for hosting

```bash
npm run build
```

The finished site goes into `dist/`. Any static web host can serve it.

## Files

Built with Vue 3, TypeScript and Vite.

- `src/App.vue`: picks the screen (sign-in, password screens or the console)
- `src/screens/LoginScreen.vue`: staff sign-in
- `src/screens/ForgotPasswordScreen.vue`: send a password reset link
- `src/screens/ResetPasswordScreen.vue`: set a new password from the email link
- `src/screens/DashboardScreen.vue`: sidebar and top bar
- `src/screens/OverviewScreen.vue`: the Dashboard numbers and tables
- `src/screens/UsersScreen.vue`: rider list, who is logged in, approve and suspend
- `src/api/users.ts`: loads riders from Supabase
- `src/components/`: shared parts (snackbar, password field, layout)
- `src/snackbar.ts`: success and error messages
- `src/supabase.ts`: Supabase connection and the staff check
- `supabase/setup.sql`: creates the staff table
- `supabase/dashboard.sql`: dashboard tables, security rules and the dashboard API
- `supabase/sample-data.sql`: optional sample riders, incidents and payments
- `src/api/dashboard.ts`: calls the dashboard API
- `src/format.ts`: numbers, rupees and "18 min ago" times
- `src/sampleData.ts`: sample numbers and users
- `src/styles.css`: colors and layout, matching the Airvox app
- `public/airvox-logo.png`: the app logo
