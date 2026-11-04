// Sample records until the portal reads from Supabase.

export type UserStatus = "Active" | "Suspended" | "Pending";
export type Platform = "Android" | "iOS";

export interface AppUser {
  id: string;
  name: string;
  phone: string;
  platform: Platform;
  joined: string;
  lastActive: string;
  wallet: number;
  status: UserStatus;
}

export const USERS: AppUser[] = [
  { id: "U-10482", name: "Manjunath K", phone: "+91 98450 11234", platform: "Android", joined: "28 Oct 2026", lastActive: "Now", wallet: 1200, status: "Active" },
  { id: "U-10481", name: "Akil Saji", phone: "+91 99001 45678", platform: "Android", joined: "27 Oct 2026", lastActive: "4 min ago", wallet: 450, status: "Active" },
  { id: "U-10479", name: "Priya Raman", phone: "+91 97411 20987", platform: "iOS", joined: "25 Oct 2026", lastActive: "1 h ago", wallet: 0, status: "Pending" },
  { id: "U-10476", name: "Rahul Menon", phone: "+91 90080 33421", platform: "Android", joined: "22 Oct 2026", lastActive: "12 min ago", wallet: 2300, status: "Active" },
  { id: "U-10470", name: "Sneha Iyer", phone: "+91 95388 76512", platform: "iOS", joined: "18 Oct 2026", lastActive: "3 days ago", wallet: 150, status: "Suspended" },
  { id: "U-10466", name: "Vikram Shetty", phone: "+91 98861 55012", platform: "Android", joined: "15 Oct 2026", lastActive: "Yesterday", wallet: 800, status: "Active" },
  { id: "U-10461", name: "Ananya Rao", phone: "+91 99459 80034", platform: "Android", joined: "11 Oct 2026", lastActive: "2 h ago", wallet: 0, status: "Active" },
  { id: "U-10455", name: "Karthik N", phone: "+91 91087 64420", platform: "iOS", joined: "6 Oct 2026", lastActive: "6 days ago", wallet: 300, status: "Active" },
];
