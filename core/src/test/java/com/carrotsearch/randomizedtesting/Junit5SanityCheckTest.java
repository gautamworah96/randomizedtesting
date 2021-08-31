package com.carrotsearch.randomizedtesting;

import org.junit.jupiter.api.Test;

public class Junit5SanityCheckTest {
  @Test
  public void passing() {
    // Ok.
    System.out.println("Passing.");
  }

  @Test
  public void failingOnAssertion() {
    System.out.println("failingOnAssertion");
    assert false : "Failing.";
  }

  @Test
  public void failingWithException() {
    System.out.println("failingWithException");
    throw new RuntimeException("Failing with exception.");
  }
}
