/*****************************************************************************
* | File      	:   LCD_2in8.cpp
* | Author      :   Waveshare team
* | Function    :   Hardware underlying interface
* | Info        :
*                Used to shield the underlying layers of each master
*                and enhance portability
*----------------
* |	This version:   V1.0
* | Date        :   2025-03-13
* | Info        :   Basic version
*
******************************************************************************/
#include "LCD_2in8.h"
#include "DEV_Config.h"

#include <stdlib.h>		//itoa()
#include <stdio.h>

LCD_2IN8_ATTRIBUTES LCD_2IN8;


/******************************************************************************
function :	Hardware reset
parameter:
******************************************************************************/
static void LCD_2IN8_Reset(void)
{
    DEV_Digital_Write(LCD_RST_PIN, 1);
    DEV_Delay_ms(100);
    DEV_Digital_Write(LCD_RST_PIN, 0);
    DEV_Delay_ms(100);
    DEV_Digital_Write(LCD_RST_PIN, 1);
    DEV_Delay_ms(100);
}

/******************************************************************************
function :	send command
parameter:
     Reg : Command register
******************************************************************************/
static void LCD_2IN8_SendCommand(UBYTE Reg)
{
    DEV_Digital_Write(LCD_DC_PIN, 0);
    DEV_Digital_Write(LCD_CS_PIN, 0);
    DEV_SPI_WriteByte(Reg);
    DEV_Digital_Write(LCD_CS_PIN, 1);
}

/******************************************************************************
function :	send data
parameter:
    Data : Write data
******************************************************************************/
static void LCD_2IN8_SendData_8Bit(UBYTE Data)
{
    DEV_Digital_Write(LCD_DC_PIN, 1);
    DEV_Digital_Write(LCD_CS_PIN, 0);
    DEV_SPI_WriteByte(Data);
    DEV_Digital_Write(LCD_CS_PIN, 1);
}

/******************************************************************************
function :	send data
parameter:
    Data : Write data
******************************************************************************/
static void LCD_2IN8_SendData_16Bit(UWORD Data)
{
    DEV_Digital_Write(LCD_DC_PIN, 1);
    DEV_Digital_Write(LCD_CS_PIN, 0);
    DEV_SPI_WriteByte((Data >> 8) & 0xFF);
    DEV_SPI_WriteByte(Data & 0xFF);
    DEV_Digital_Write(LCD_CS_PIN, 1);
}

/******************************************************************************
function :	Initialize the lcd register
parameter:
******************************************************************************/
static void LCD_2IN8_InitReg(void)
{
    LCD_2IN8_SendCommand(0x29); // Display on
    sleep_ms(10);
    LCD_2IN8_SendCommand(0x11);
    sleep_ms(10); // ms

    LCD_2IN8_SendCommand(0x3A);     
    LCD_2IN8_SendData_8Bit(0x05);   //LCD_2IN8_SendData_8Bit(0x66);

    LCD_2IN8_SendCommand(0xB2);
    LCD_2IN8_SendData_8Bit(0x0C);
    LCD_2IN8_SendData_8Bit(0x0C);
    LCD_2IN8_SendData_8Bit(0x00);
    LCD_2IN8_SendData_8Bit(0x33);
    LCD_2IN8_SendData_8Bit(0x33);

    LCD_2IN8_SendCommand(0xB7);
    LCD_2IN8_SendData_8Bit(0x75); // VGH=14.97V,VGL=-7.67V

    LCD_2IN8_SendCommand(0xBB);
    LCD_2IN8_SendData_8Bit(0x1A);

    LCD_2IN8_SendCommand(0xC0);
    LCD_2IN8_SendData_8Bit(0x2C);

    LCD_2IN8_SendCommand(0xC2);
    LCD_2IN8_SendData_8Bit(0x01);
    LCD_2IN8_SendData_8Bit(0xFF);

    LCD_2IN8_SendCommand(0xC3);
    LCD_2IN8_SendData_8Bit(0x13);

    LCD_2IN8_SendCommand(0xC4);
    LCD_2IN8_SendData_8Bit(0x20);

    LCD_2IN8_SendCommand(0xC6);
    LCD_2IN8_SendData_8Bit(0x0F);

    LCD_2IN8_SendCommand(0xD0);
    LCD_2IN8_SendData_8Bit(0xA4);
    LCD_2IN8_SendData_8Bit(0xA1);

    LCD_2IN8_SendCommand(0xD6);
    LCD_2IN8_SendData_8Bit(0xA1);

    LCD_2IN8_SendCommand(0xE0);
    LCD_2IN8_SendData_8Bit(0xD0);
    LCD_2IN8_SendData_8Bit(0x0D);
    LCD_2IN8_SendData_8Bit(0x14);
    LCD_2IN8_SendData_8Bit(0x0D);
    LCD_2IN8_SendData_8Bit(0x0D);
    LCD_2IN8_SendData_8Bit(0x09);
    LCD_2IN8_SendData_8Bit(0x38);
    LCD_2IN8_SendData_8Bit(0x44);
    LCD_2IN8_SendData_8Bit(0x4E);
    LCD_2IN8_SendData_8Bit(0x3A);
    LCD_2IN8_SendData_8Bit(0x17);
    LCD_2IN8_SendData_8Bit(0x18);
    LCD_2IN8_SendData_8Bit(0x2F);
    LCD_2IN8_SendData_8Bit(0x30);

    LCD_2IN8_SendCommand(0xE1);
    LCD_2IN8_SendData_8Bit(0xD0);
    LCD_2IN8_SendData_8Bit(0x09);
    LCD_2IN8_SendData_8Bit(0x0F);
    LCD_2IN8_SendData_8Bit(0x08);
    LCD_2IN8_SendData_8Bit(0x07);
    LCD_2IN8_SendData_8Bit(0x14);
    LCD_2IN8_SendData_8Bit(0x37);
    LCD_2IN8_SendData_8Bit(0x44);
    LCD_2IN8_SendData_8Bit(0x4D);
    LCD_2IN8_SendData_8Bit(0x38);
    LCD_2IN8_SendData_8Bit(0x15);
    LCD_2IN8_SendData_8Bit(0x16);
    LCD_2IN8_SendData_8Bit(0x2C);
    LCD_2IN8_SendData_8Bit(0x2E);

    LCD_2IN8_SendCommand(0x21);

    LCD_2IN8_SendCommand(0x29);

    LCD_2IN8_SendCommand(0x2C);
}

/********************************************************************************
function:	Set the resolution and scanning method of the screen
parameter:
		Scan_dir:   Scan direction
********************************************************************************/
static void LCD_2IN8_SetAttributes(UBYTE Scan_dir)
{
    //Get the screen scan direction
    LCD_2IN8.SCAN_DIR = Scan_dir;
    UBYTE MemoryAccessReg = 0x00;

    //Get GRAM and LCD width and height
    if(Scan_dir == HORIZONTAL) {
        LCD_2IN8.HEIGHT	= LCD_2IN8_HEIGHT;
        LCD_2IN8.WIDTH   = LCD_2IN8_WIDTH;
        MemoryAccessReg = 0X00;
    } else {
        LCD_2IN8.HEIGHT	= LCD_2IN8_HEIGHT;       
        LCD_2IN8.WIDTH   = LCD_2IN8_WIDTH;
        MemoryAccessReg = 0X08;
    }

    // Set the read / write scan direction of the frame memory
    LCD_2IN8_SendCommand(0X11); 
    sleep_ms(120); 
    LCD_2IN8_SendCommand(0X36); //MX, MY, RGB mode
    LCD_2IN8_SendData_8Bit(MemoryAccessReg);	//0x08 set RGB
}

/********************************************************************************
function :	Initialize the lcd
parameter:
********************************************************************************/
void LCD_2IN8_Init(UBYTE Scan_dir)
{
    //Hardware reset
    LCD_2IN8_Reset();

    //Set the resolution and scanning method of the screen
    LCD_2IN8_SetAttributes(Scan_dir);
    
    //Set the initialization register
    LCD_2IN8_InitReg();
}

void LCD_2IN8_SetPower(bool On)
{
    if (On) {
        LCD_2IN8_SendCommand(0x11); // Sleep out
        sleep_ms(120);
        LCD_2IN8_SendCommand(0x29); // Display on
    } else {
        LCD_2IN8_SendCommand(0x28); // Display off
        sleep_ms(20);
        LCD_2IN8_SendCommand(0x10); // Sleep in
        sleep_ms(120);
    }
}

/********************************************************************************
function:	Sets the start position and size of the display area
parameter:
		Xstart 	:   X direction Start coordinates
		Ystart  :   Y direction Start coordinates
		Xend    :   X direction end coordinates
		Yend    :   Y direction end coordinates
********************************************************************************/
void LCD_2IN8_SetWindows(UWORD Xstart, UWORD Ystart, UWORD Xend, UWORD Yend)
{
    //set the X coordinates
    LCD_2IN8_SendCommand(0x2A);
    LCD_2IN8_SendData_8Bit(Xstart >> 8);
    LCD_2IN8_SendData_8Bit(Xstart & 0xff);
	LCD_2IN8_SendData_8Bit((Xend - 1) >> 8);
    LCD_2IN8_SendData_8Bit((Xend - 1) & 0xFF);

    //set the Y coordinates
    LCD_2IN8_SendCommand(0x2B);
    LCD_2IN8_SendData_8Bit(Ystart >> 8);
	LCD_2IN8_SendData_8Bit(Ystart & 0xff);
	LCD_2IN8_SendData_8Bit((Yend - 1) >> 8);
    LCD_2IN8_SendData_8Bit((Yend - 1) & 0xff);

    LCD_2IN8_SendCommand(0X2C);
}

/******************************************************************************
function :	Sends the image buffer in RAM to displays
parameter:
******************************************************************************/
void LCD_2IN8_Display(UWORD *Image)
{
    UWORD j;
    LCD_2IN8_SetWindows(0, 0, LCD_2IN8.WIDTH, LCD_2IN8.HEIGHT);
    // LCD_2IN8_SetWindows(0, 0, LCD_2IN8.HEIGHT, LCD_2IN8.WIDTH);
    DEV_Digital_Write(LCD_DC_PIN, 1);
    DEV_Digital_Write(LCD_CS_PIN, 0);
    for (j = 0; j < LCD_2IN8.HEIGHT; j++) {
        DEV_SPI_Write_nByte((UBYTE *)Image+LCD_2IN8.WIDTH*2*j,LCD_2IN8.WIDTH*2);
    }
    DEV_Digital_Write(LCD_CS_PIN, 1);
    LCD_2IN8_SendCommand(0x29);
}

void LCD_2IN8_DisplayWindows(UWORD Xstart, UWORD Ystart, UWORD Xend, UWORD Yend, UWORD *Image)
{
    // display
    UDOUBLE Addr = 0;

    UWORD j;
    LCD_2IN8_SetWindows(Xstart, Ystart, Xend , Yend);
    DEV_Digital_Write(LCD_DC_PIN, 1);
    DEV_Digital_Write(LCD_CS_PIN, 0);
    for (j = Ystart; j < Yend - 1; j++) {
        Addr = Xstart + j * LCD_2IN8.WIDTH ;
        DEV_SPI_Write_nByte((uint8_t *)&Image[Addr], (Xend-Xstart)*2);
    }
    DEV_Digital_Write(LCD_CS_PIN, 1);
}

void LCD_2IN8_DisplayPoint(UWORD X, UWORD Y, UWORD Color)
{
    LCD_2IN8_SetWindows(X,Y,X,Y);
    LCD_2IN8_SendData_16Bit(Color);
}

void  Handler_2IN8_LCD(int signo)
{
    //System Exit
    printf("\r\nHandler:Program stop\r\n");     
    DEV_Module_Exit();
	exit(0);
}

