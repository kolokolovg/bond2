package ru.onlychill.bonds2;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

class CouponPropertiesTest {

  @Property
  void neverNegativeAndNeverAboveFaceValue(
      @ForAll @LongRange(min = 0, max = 1_000_000_000_000L) long face,
      @ForAll @IntRange(min = 0, max = Coupon.MAX_RATE_BPS) int rate,
      @ForAll @IntRange(min = 1, max = 365) int perYear) {
    assertThat(Coupon.payment(face, rate, perYear)).isBetween(0L, face);
  }

  @Property
  void monotonicInRate(
      @ForAll @LongRange(min = 0, max = 1_000_000_000_000L) long face,
      @ForAll @IntRange(min = 0, max = Coupon.MAX_RATE_BPS - 1) int rate,
      @ForAll @IntRange(min = 1, max = 12) int perYear) {
    assertThat(Coupon.payment(face, rate, perYear))
        .isLessThanOrEqualTo(Coupon.payment(face, rate + 1, perYear));
  }

  @Property
  void allPaymentsNeverExceedAnnualCoupon(
      @ForAll @LongRange(min = 0, max = 1_000_000_000_000L) long face,
      @ForAll @IntRange(min = 0, max = Coupon.MAX_RATE_BPS) int rate,
      @ForAll @IntRange(min = 1, max = 12) int perYear) {
    long annual = Coupon.payment(face, rate, 1);
    assertThat(Coupon.payment(face, rate, perYear) * perYear).isLessThanOrEqualTo(annual);
  }
}
