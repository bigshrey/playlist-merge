package com.amazonmusic.scraper;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/**
 * Interface for authentication-related operations (Playwright context lifecycle and sign-in workflows).
 */
public interface AuthServiceInterface {
    /**
     * Initializes the authentication service and ensures necessary directories exist.
     */
    void init();
    
    /**
     * Creates a new browser context or restores an existing one from saved state.
     * @param browser Playwright browser instance
     * @return BrowserContext with restored session state if available
     */
    BrowserContext createOrRestoreContext(Browser browser);
    
    /**
     * Sets up a browser context with appropriate configurations for scraping.
     * @param playwright Playwright instance
     * @return Configured BrowserContext ready for use
     */
    BrowserContext setupBrowserContext(Playwright playwright);
    
    /**
     * Saves the current browser context's storage state for session persistence.
     * @param context Browser context to save
     */
    void saveStorageState(BrowserContext context);
    
    /**
     * Attempts automated sign-in to Amazon Music using stored credentials or prompts.
     * @param page Playwright page instance for sign-in operations
     */
    void automateSignIn(Page page);
    
    /**
     * Robust manual login workflow: prompts user, waits for completion, and handles browser closure, network errors, and user abort.
     * Optionally accepts a Runnable callback for extensibility.
     * @param page Playwright page instance
     * @param onComplete Optional callback to run after successful manual login (may be null)
     */
    void handleManualLogin(Page page, Runnable onComplete);

    /**
     * Prints session cookies from the browser context for debugging purposes.
     * @param context Browser context containing session cookies
     */
    void printSessionCookies(BrowserContext context);
    
    /**
     * Waits for authentication UI elements to appear on the page.
     * @param page Playwright page instance to monitor
     */
    void waitForAuthUi(Page page);
    
    /**
     * Checks if the current page is authenticated using both session cookies and DOM elements.
     * @param page Playwright page instance to check
     * @return true if authenticated, false otherwise
     */
    boolean isAuthenticated(Page page);
}
