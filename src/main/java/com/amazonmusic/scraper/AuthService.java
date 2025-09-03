package com.amazonmusic.scraper;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.PlaywrightException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class AuthService implements AuthServiceInterface {
    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    // Make the service instantiable for DI and mocking
    public AuthService() {}

    /**
     * Lightweight initialization for AuthService. Ensures artifact directories exist and logs current env hints.
     * Safe to call multiple times.
     */
    public void init() {
        try {
            Files.createDirectories(Paths.get("scraped-data"));
            logger.info("AuthService initialized. scraped-data directory ensured.");
            String email = System.getenv("AMAZON_MUSIC_EMAIL");
            if (email != null && !email.isBlank()) {
                logger.info("Environment credential detected for AMAZON_MUSIC_EMAIL (will attempt auto sign-in).");
            } else {
                logger.info("No AMAZON_MUSIC_EMAIL env var detected; manual sign-in may be required.");
            }
        } catch (Exception e) {
            logger.warn("AuthService init failed to create directories: {}", e.getMessage());
        }
    }

    /**
     * Create or restore a Playwright BrowserContext, optionally using saved storage state.
     */
    public BrowserContext createOrRestoreContext(Browser browser) {
        String storagePath = "scraped-data/storage-state.json";
        try {
            Files.createDirectories(Paths.get("scraped-data"));
        } catch (IOException ignored) {}
        Browser.NewContextOptions contextOptions = new Browser.NewContextOptions().setViewportSize(1920, 1080)
            .setRecordHarPath(Paths.get("scraped-data", "session.har"));
        java.nio.file.Path storageFile = java.nio.file.Paths.get(storagePath);
        if (java.nio.file.Files.exists(storageFile)) {
            // Try to sanity-check the storage state file before handing it to Playwright.
            try {
                String content = java.nio.file.Files.readString(storageFile);
                // find first non-whitespace char
                int idx = 0;
                while (idx < content.length() && Character.isWhitespace(content.charAt(idx))) idx++;
                char first = idx < content.length() ? content.charAt(idx) : '\0';
                if (first == '{' || first == '[') {
                    contextOptions.setStorageStatePath(storageFile);
                    logger.info("Using existing storage state from {} to restore session.", storagePath);
                } else {
                    // Backup the file and continue without it
                    java.nio.file.Path backup = java.nio.file.Paths.get(storagePath + ".invalid-" + System.currentTimeMillis());
                    try {
                        java.nio.file.Files.move(storageFile, backup);
                        logger.warn("storage-state.json appears invalid. Backed up to {} and will start a fresh context.", backup);
                    } catch (Exception mv) {
                        logger.warn("storage-state.json appears invalid and could not be backed up: {}. It will be ignored.", mv.getMessage());
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to read or validate storage state '{}': {}. Ignoring and starting fresh.", storagePath, e.getMessage());
            }
        } else {
             logger.info("No existing storage state found; starting a fresh context.");
         }
         // Try to create the context now so we can recover if Playwright fails to read the file
         try {
             return browser.newContext(contextOptions);
         } catch (Exception e) {
             logger.warn("Playwright failed to create context using storage state: {}. Will attempt to recover by ignoring storage state.", e.getMessage());
             // If we attempted to use a storage file, back it up to avoid repeated failures
             try {
                 if (java.nio.file.Files.exists(storageFile)) {
                     java.nio.file.Path backup = java.nio.file.Paths.get(storagePath + ".playwright-error-" + System.currentTimeMillis());
                     java.nio.file.Files.move(storageFile, backup);
                     logger.warn("Backed up problematic storage-state.json to {} and will create a fresh context.", backup);
                 }
             } catch (Exception mv) {
                 logger.warn("Failed to backup problematic storage-state.json: {}", mv.getMessage());
             }
             // Clear storageStatePath and retry
             try {
                 Browser.NewContextOptions fresh = new Browser.NewContextOptions().setViewportSize(1920, 1080).setRecordHarPath(Paths.get("scraped-data", "session.har"));
                 return browser.newContext(fresh);
             } catch (Exception ex) {
                 logger.error("Failed to create a fresh browser context: {}", ex.getMessage());
                 throw new RuntimeException(ex);
             }
         }
    }

    /**
     * Launches a Playwright browser and returns a context created/restored via createOrRestoreContext.
     */
    public BrowserContext setupBrowserContext(Playwright playwright) {
        try {
            var browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false));
            return createOrRestoreContext(browser);
        } catch (Exception e) {
            logger.error("Failed to launch browser: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * Saves the current browser context storage state to disk.
     */
    public void saveStorageState(BrowserContext context) {
        String storagePath = "scraped-data/storage-state.json";
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get("scraped-data"));
            context.storageState(new BrowserContext.StorageStateOptions().setPath(java.nio.file.Paths.get(storagePath)));
            logger.info("Saved storage state to {}", storagePath);
        } catch (Exception e) {
            logger.warn("Failed to save storage state: {}", e.getMessage());
        }
    }

    /**
     * Attempts an automated sign-in using environment credentials, otherwise falls back to
     * prompting the user to complete manual login in the visible browser window.
     */
    public void automateSignIn(Page page) {
        try {
            page.waitForTimeout(500); // Let initial scripts run
            if (page.isClosed()) {
                logger.warn("Page already closed when trying to sign in.");
                return;
            }
            Locator signIn = null;
            try {
                var byRole = page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign in").setExact(false));
                if (byRole.count() > 0) signIn = byRole.first();
            } catch (PlaywrightException ignored) {}
            if (signIn == null) {
                try {
                    var byText = page.getByText("Sign in", new Page.GetByTextOptions().setExact(false));
                    if (byText.count() > 0) signIn = byText.first();
                } catch (PlaywrightException ignored) {}
            }
            if (signIn == null) {
                try {
                    signIn = page.locator("a[href*='signin'], [data-test-id='sign-in-button'], button:has-text('Sign in'), button:has-text('Sign In')");
                    if (signIn.count() == 0) signIn = null;
                } catch (PlaywrightException ignored) {
                    signIn = null;
                }
            }

            if (signIn != null) {
                try {
                    signIn.scrollIntoViewIfNeeded();
                    signIn.click(new Locator.ClickOptions().setTimeout(15000));
                    logger.info("Sign-In control clicked. Waiting for credential entry form...");
                    page.waitForSelector("#ap_email, #ap_password, form[name='signIn'], input[name='email'], input[type='email']", new Page.WaitForSelectorOptions().setTimeout(20000));
                    logger.info("Credential entry page detected. Attempting auto sign-in if credentials are set...");
                    String email = System.getenv("AMAZON_MUSIC_EMAIL");
                    String password = System.getenv("AMAZON_MUSIC_PASSWORD");
                    if (email != null && password != null) {
                        try {
                            Locator emailInput = page.locator("#ap_email, input[name='email'], input[type='email']");
                            if (emailInput.count() > 0) {
                                emailInput.first().fill(email);
                                logger.info("Filled email field.");
                            }
                            Locator continueBtn = page.locator("#continue, button:has-text('Continue')");
                            if (continueBtn.count() > 0) {
                                continueBtn.first().click();
                                logger.info("Clicked continue button.");
                            }
                            page.waitForSelector("#ap_password, input[name='password'], input[type='password']", new Page.WaitForSelectorOptions().setTimeout(15000));
                            Locator passwordInput = page.locator("#ap_password, input[name='password'], input[type='password']");
                            if (passwordInput.count() > 0) {
                                passwordInput.first().fill(password);
                                logger.info("Filled password field.");
                            }
                            Locator signInBtn = page.locator("#signInSubmit, button:has-text('Sign-In'), button:has-text('Sign in'), button:has-text('Sign In')");
                            if (signInBtn.count() > 0) {
                                signInBtn.first().click();
                                logger.info("Clicked sign-in submit button.");
                            }
                            page.waitForLoadState();
                            page.waitForTimeout(5000);
                            logger.info("Auto sign-in completed.");
                            return;
                        } catch (Exception e) {
                            logger.error("Auto sign-in failed, please complete manually: {}", e.getMessage());
                        }
                    }
                    // fallback to manual
                    displayBrowserForManualLogin(page);
                    return;
                } catch (PlaywrightException pe) {
                    logger.debug("Failed to click sign-in control: {}", pe.getMessage());
                }
            }
            logger.info("No immediate sign-in control detected. Proceeding — manual login may still be required later.");
        } catch (Exception e) {
            logger.error("Failed to navigate to the sign-in workflow: {}. Manual intervention may be required.", e.getMessage());
        }
    }

    /**
     * Prompts the user to complete manual login in the browser and waits for confirmation.
     */
    public void displayBrowserForManualLogin(Page page) {
        try {
            logger.info("Please complete manual login in the opened browser window.");
            logger.info("Current page URL: {}", page.url());
            try {
                Files.createDirectories(Paths.get("scraped-data"));
                Files.writeString(Paths.get("scraped-data", "manual-login-url.txt"), page.url());
            } catch (IOException e) {
                logger.error("Failed to save manual login URL: {}", e.getMessage());
            }
            logger.info("Focus the browser window opened by this tool and complete authentication. After completing login, press Enter here to continue.");
            System.out.println("Press Enter after completing login in the browser...");
            int input = System.in.read();
            if (input == -1) {
                logger.warn("No input detected, proceeding anyway.");
            } else {
                logger.info("User signalled to continue after manual login.");
            }
            page.waitForLoadState();
            page.waitForTimeout(15000);
        } catch (IOException e) {
            logger.error("Error while waiting for user input for manual login: {}", e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error during manual login handling: {}", e.getMessage());
        }
    }

    /**
     * Prints session cookies for a browser context; useful for debugging auth state.
     */
    public void printSessionCookies(BrowserContext context) {
        try {
            var cookies = context.cookies();
            logger.info("Session cookies after login:");
            for (var cookie : cookies) {
                logger.info("{}={} cookies", cookie.name, cookie.value);
            }
        } catch (Exception e) {
            logger.warn("Failed to print session cookies: {}", e.getMessage());
        }
    }

    /**
     * Lazy-reactive wait for authentication-related UI to appear (sign-in controls or profile elements).
     */
    public void waitForAuthUi(Page page) {
        long maxWaitMs = Long.parseLong(System.getenv().getOrDefault("SCRAPER_AUTH_WAIT_MS", "10000"));
        long deadline = System.currentTimeMillis() + maxWaitMs;
        int poll = 250;
        int maxPoll = 2000;
        String profileSel = "[aria-label='Account'], [data-test-id='profile-menu'], img[alt*='profile'], img[alt*='avatar'], button:has([data-icon='profile'])";
        String signInSel = "button:has-text('Sign in'), button:has-text('Sign In'), a[href*='signin'], [data-test-id='sign-in-button']";
        String combined = profileSel + ", " + signInSel;

        while (System.currentTimeMillis() < deadline) {
            try {
                Locator profile = page.locator(profileSel);
                if (profile != null && profile.count() > 0) return;
                Locator signIn = page.getByText("Sign in", new Page.GetByTextOptions().setExact(false));
                if (signIn != null && signIn.count() > 0) return;
            } catch (Exception ignored) {}

            try {
                page.waitForSelector(combined, new Page.WaitForSelectorOptions().setTimeout(poll));
                return;
            } catch (PlaywrightException ignored) {}

            try { page.waitForTimeout(Math.min(poll, 500)); } catch (Exception ignored) {}
            poll = Math.min(maxPoll, poll * 2);
        }
    }

    /**
     * Waits for user confirmation after manual sign-in.
     */
    public void waitForUserToContinue() {
        logger.info("After you finish signing in, press Enter here to continue scraping.");
        try {
            System.out.println("Press Enter to continue...");
            int input = System.in.read();
            if (input == -1) {
                logger.warn("No input detected, proceeding.");
            } else {
                logger.info("Input received, proceeding.");
            }
        } catch (IOException e) {
            logger.error("Error waiting for user input: {}", e.getMessage());
        }
    }
}
