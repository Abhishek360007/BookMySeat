package com.abhishekraj.BookMySeat.notification;

/** One email, already rendered: a plain-text body for every client and an HTML body for those that show it. */
public record EmailMessage(String to, String subject, String text, String html) {}
