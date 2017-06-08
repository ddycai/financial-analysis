package io.github.frigidrain;

import io.github.frigidrain.PortfolioOuterClass.Portfolio;
import io.github.frigidrain.PortfolioOuterClass.Portfolio.StockQuantity;
import io.github.frigidrain.PortfolioOuterClass.PortfolioDistribution;
import io.github.frigidrain.PortfolioOuterClass.PortfolioDistribution.StockProportion;
import java.io.IOException;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import yahoofinance.YahooFinance;

/** Determines the quantity of stock to buy for a desired portfolio distribution. */
public class InvestmentProportions {

  private final ExecutorService executor = Executors.newFixedThreadPool(1);
  private final PortfolioDistribution distribution;
  private final Future<Map<String, Double>> priceFuture;
  private Map<String, Double> prices;

  private InvestmentProportions(PortfolioDistribution distribution) {
    this.distribution = distribution;
    this.priceFuture = executor.submit(new FetchPrices(distribution));
  }

  private Portfolio computePortfolio(final double maxInvestment)
      throws ExecutionException, InterruptedException {
    Portfolio best = null;
    double minDeviation = Double.MAX_VALUE;
    final double minInvestment = Math.round(maxInvestment * .9);
    for (double investment = minInvestment; investment <= maxInvestment; investment++) {
      Portfolio portfolio = portfolioForInvestment(investment);
      double deviation = computeDeviation(distribution, portfolio);
      if (best == null || deviation < minDeviation) {
        best = portfolio;
        minDeviation = deviation;
      }
    }
    return best;
  }

  private Portfolio portfolioForInvestment(double investment)
      throws ExecutionException, InterruptedException {
    if (prices == null) {
      this.prices = priceFuture.get();
      executor.shutdown();
    }
    Portfolio.Builder portfolio = Portfolio.newBuilder();
    for (StockProportion proportion : distribution.getStockList()) {
      String ticker = proportion.getTicker();
      int quantity = (int) Math.round(proportion.getPercentage() * investment / prices.get(ticker));
      portfolio.addStock(
          StockQuantity.newBuilder()
              .setTicker(ticker)
              .setQuantity(quantity)
              .setPrice(prices.get(ticker)));
    }
    return portfolio.build();
  }

  public static void main(String[] args)
      throws IOException, ExecutionException, InterruptedException {
    PortfolioDistribution distribution =
        PortfolioDistribution.newBuilder()
            .addStock(StockProportion.newBuilder().setTicker("VTI").setPercentage(.36))
            .addStock(StockProportion.newBuilder().setTicker("VEA").setPercentage(.25))
            .addStock(StockProportion.newBuilder().setTicker("VWO").setPercentage(.19))
            .addStock(StockProportion.newBuilder().setTicker("VIG").setPercentage(.10))
            .addStock(StockProportion.newBuilder().setTicker("BND").setPercentage(.10))
            .build();

    InvestmentProportions investmentProportions = new InvestmentProportions(distribution);
    Scanner scanner = new Scanner(System.in);
    System.out.println("Around how much money are you investing?");
    final double input = scanner.nextDouble();
    print(investmentProportions.computePortfolio(input));
  }

  static class FetchPrices implements Callable<Map<String, Double>> {

    private final PortfolioDistribution distribution;

    FetchPrices(PortfolioDistribution distribution) {
      this.distribution = distribution;
    }

    @Override
    public Map<String, Double> call() throws Exception {
      String[] tickers =
          distribution
              .getStockList()
              .stream()
              .map(StockProportion::getTicker)
              .toArray(String[]::new);

      return YahooFinance.get(tickers)
          .entrySet()
          .stream()
          .collect(
              Collectors.toMap(
                  Map.Entry::getKey,
                  entry -> entry.getValue().getQuote().getPrice().doubleValue()));
    }
  }

  private static void print(Portfolio portfolio) {
    double total = 0;
    for (StockQuantity stock : portfolio.getStockList()) {
      total += stock.getQuantity() * stock.getPrice();
    }

    System.out.format("Total investment: %.2f%n", total);
    for (StockQuantity stock : portfolio.getStockList()) {
      System.out.format(
          "%-6s %-6.2f x %d (%.2f%%)%n",
          stock.getTicker(),
          stock.getPrice(),
          stock.getQuantity(),
          stock.getPrice() * stock.getQuantity() * 100.0 / total);
    }
  }

  /** This objective function tries to minimize the deviation from the desired distribution. */
  private static double computeDeviation(PortfolioDistribution distribution, Portfolio portfolio) {
    Map<String, Double> desiredPercentage =
        distribution
            .getStockList()
            .stream()
            .collect(Collectors.toMap(StockProportion::getTicker, StockProportion::getPercentage));

    double total = 0;
    for (StockQuantity stock : portfolio.getStockList()) {
      total += stock.getQuantity() * stock.getPrice();
    }

    double totalDeviation = 0;
    for (StockQuantity stock : portfolio.getStockList()) {
      double actualPercentage = stock.getQuantity() * stock.getPrice() / total;
      totalDeviation += Math.abs(desiredPercentage.get(stock.getTicker()) - actualPercentage);
    }
    return totalDeviation;
  }
}
