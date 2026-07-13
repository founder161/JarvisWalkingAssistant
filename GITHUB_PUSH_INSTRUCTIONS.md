# Push to GitHub Instructions

## Step 1: Create GitHub Personal Access Token

1. Go to https://github.com/settings/tokens
2. Click "Generate new token (classic)"
3. Name: "Jarvis-Mobile-Build"
4. Select scope: ✅ `repo` (full control of private repositories)
5. Click "Generate token"
6. **Copy the token** (you won't see it again)

## Step 2: Create Empty Repository on GitHub

1. Go to https://github.com/new
2. Repository name: `JarvisWalkingAssistant`
3. **Do NOT** initialize with README, .gitignore, or license
4. Click "Create repository"
5. You'll see a page with commands like:
   ```
   git remote add origin https://github.com/YOUR_USERNAME/JarvisWalkingAssistant.git
   git branch -M main
   git push -u origin main
   ```

## Step 3: Push Locally (Run This)

From `/root/JarvisWalkingAssistant/` directory:

```bash
cd ~/JarvisWalkingAssistant

# Replace YOUR_USERNAME with your actual GitHub username
git remote add origin https://github.com/YOUR_USERNAME/JarvisWalkingAssistant.git

# Rename branch to main
git branch -M main

# Push to GitHub
git push -u origin main
```

When prompted for password: **Paste your Personal Access Token** (from Step 1)

## Step 4: Verify Build Started

1. Go to https://github.com/YOUR_USERNAME/JarvisWalkingAssistant
2. Click "Actions" tab
3. You should see "Build Jarvis APKs" workflow running
4. Wait 5-10 minutes for build to complete

## Step 5: Download APKs

1. Click "Build Jarvis APKs" workflow result
2. Scroll to "Artifacts" section
3. Download `Phone-Jarvis-Assistant` and `Watch-Jarvis-Assistant` ZIP files
4. Extract both ZIP files
5. You'll have `app-debug.apk` and `wear-debug.apk`

## Installation

Install on Z Flip 6 via ADB or file manager:
```bash
adb install app-debug.apk
adb install wear-debug.apk
```

Or manually:
- Transfer APKs to phone via USB
- Open with system app installer
- Grant permissions when prompted

---

**Important:** Replace `YOUR_KEY_HERE` in `JarvisForegroundService.kt` with your Claude API key before pushing!
