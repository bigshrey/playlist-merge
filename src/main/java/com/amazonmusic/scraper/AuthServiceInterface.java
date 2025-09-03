package com.amazonmusic.scraper;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/**
 * Interface for authentication-related operations (Playwright context lifecycle and sign-in workflows).
 */
public interface AuthServiceInterface {
    void init();
    BrowserContext createOrRestoreContext(Browser browser);
    BrowserContext setupBrowserContext(Playwright playwright);
    void saveStorageState(BrowserContext context);
    void automateSignIn(Page page);
    void displayBrowserForManualLogin(Page page);
    void printSessionCookies(BrowserContext context);
    void waitForAuthUi(Page page);
    void waitForUserToContinue();
}

