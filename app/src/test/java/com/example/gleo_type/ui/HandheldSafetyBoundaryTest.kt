package com.example.gleo_type.ui
import com.example.gleo_type.ui.app.*
import com.example.gleo_type.vehicle.*
import org.junit.Assert.*
import org.junit.Test
class HandheldSafetyBoundaryTest {
 @Test fun handheldPermissionCannotUnlockCarHearts() {
  val phone=DrivingUx(true,false,0,"HANDHELD_LOCAL_UI_NOT_CAR_PERMISSION",100)
  assertTrue(phone.complexAllowed);assertTrue(phone.label.contains("차량 편집 권한 아님"))
  val gate=HeartsEditGate();val c=CarConnectionSnapshot(CarConnectionState.CONNECTED,1)
  assertFalse(gate.allowed(phone,c,100));assertFalse(gate.allowed(phone.copy(observedElapsedMs=101),c,102))
 }
}
