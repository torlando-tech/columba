# Columba Use Manual

**Simple Installation & Setup Guide**

## What is Columba?

Columba is a secure, peer-to-peer messaging application designed to work **with or without the Internet**.
It can operate over bluetooth, Wi-Fi, mobile data, and even radio, using a technology called **Reticulum**.

Columba is especially useful when:

* Internet access is unreliable or unavailable
* Privacy and encryption matter
* You want resilient, off-grid communication

Official project repository:
[https://github.com/torlando-tech/columba](https://github.com/torlando-tech/columba)

---

## What You Need Before You Start

* An **Android phone** (Android 12 or newer recommended)
* About **100 MB of free storage**

You **do not** need:

* A phone number
* An email address
* A central server account

---

## Step 1 – Install Columba on Android

### Option A – Install from APK

1. Open the Columba GitHub page:
   [https://github.com/torlando-tech/columba](https://github.com/torlando-tech/columba)
2. Go to **Releases**
3. Download the latest **Columba Android APK**
4. On your phone:

   * Open **Settings → Security**
   * Enable **Install unknown apps**
5. Tap the downloaded APK to install

> Android may warn you about installing from outside the Play Store. This is normal for open-source apps.

---

## Step 2 – First Launch

When you open Columba for the first time:

* The app **creates a secure identity automatically**
* No registration is required
* No personal data is requested

What happens behind the scenes:

* A cryptographic identity is generated
* This identity replaces usernames, phone numbers, or email addresses


## Step 3 – Basic Configuration

Open **Settings** in Columba.

### Network

Leave defaults unless you know what you are doing:

* Reticulum enabled
* Local discovery enabled
* Automatic routing enabled

These defaults allow Columba to:

* Find peers automatically
* Switch between Internet, Local Wi-Fi, or bluetooth

### Identity

* Already created automatically
* You should  set a display name using the wizard. if you skipped that step then you  can set your display name in the settings

## Step 4 – Sending Your First Message

### Using announces
1. open **Announces**
2. tap on the name of a contact
3. tap start chat

### using chat
1. Open **Chats**
2. Choose **New Conversation**
3. Add a contact by:

   * Scanning a QR code
   * Pasting a destination string
4. Type a message
5. Press **Send**

In both cases if the recipient is reachable:

* Message is delivered immediately

If not:

* Message is **stored securely**
* Delivered automatically when the recipient becomes available

---

## Step 5 – Offline and Mesh Usage

Columba also works even when:

* Internet is down
* Cellular service is unavailable

Examples:

* Phones connected through bluetooth
* Two phones on the same Wi-Fi network
* Devices linked via LORA radio(Rnodes)

You do not need to change anything:

* Columba and Reticulum select the best available way!!!
  

## Basic Troubleshooting

**Messages not delivered immediately**

* This is normal in delay-tolerant networks
* Messages will arrive once a path exists

**No peers found**

* Ensure both devices have Columba running
* Ensure Wi-Fi or another shared medium is available
* Restart Columba

**App seems idle**

* Reticulum is event-driven; silence often means no traffic, not failure
* Restart Columba

---

# Understanding Reticulum

## What Is Reticulum?

Reticulum is the privacy focus networking system that Columba uses.

Think of it as:

> “A private, secure alternative to the Internet that works anywhere.”

It does **not** rely on:

* Internet providers, Central servers, IP addresses, Phone numbers


## How Reticulum Is Different 

### Traditional Internet

* Needs central infrastructure
* Depends on ISPs
* Unencrypted by default
* Breaks easily during outages

### Reticulum

* Decentralized
* Peer-to-peer
* Always encrypted
* Keeps working during disruptions

Reticulum was designed for:

* Security and sovereignty 
* Unreliable links
* Off-grid operation


## Why Columba + Reticulum Matters

Together they provide:

* Censure free, private messaging
* Communication without infrastructure
* Resilience during outages
* Independence from telecom providers

This is increasingly relevant for:

* Emergency communication
* Remote communities
* Privacy-conscious users
